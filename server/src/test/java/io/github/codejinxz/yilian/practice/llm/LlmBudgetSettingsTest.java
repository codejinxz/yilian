package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * {@link LlmBudgetSettings} 单测：fail-closed 解析（键缺失/JSON 损坏/版本不识别/条目非法一律按 「未设预算 +
 * 无定价」或跳条目处理）与写路径形状（schemaVersion/键结构，与 read 往返一致）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmBudgetSettingsTest {

    @Mock private AppSettingService appSettingService;

    private LlmBudgetSettings settings;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        settings = new LlmBudgetSettings(appSettingService, objectMapper);
    }

    /** 键缺失：未设预算 + 空定价表（护栏不生效的直通判据）。 */
    @Test
    @DisplayName("键缺失按未设预算处理")
    void missingKeyMeansNoBudget() {
        when(appSettingService.findValue(LlmBudgetSettings.SETTING_KEY))
                .thenReturn(Optional.empty());

        LlmBudgetSettings.Config config = settings.read();

        assertThat(config.hasBudget()).isFalse();
        assertThat(config.monthlyBudgetCny()).isNull();
        assertThat(config.pricing()).isEmpty();
    }

    /** JSON 损坏/版本不识别/预算非正：一律按未设预算处理（fail-closed，不放大成调用失败）。 */
    @Test
    @DisplayName("损坏/版本不识别/非正预算按未设处理")
    void corruptOrInvalidMeansNoBudget() {
        when(appSettingService.findValue(LlmBudgetSettings.SETTING_KEY))
                .thenReturn(Optional.of("{not-json"))
                .thenReturn(Optional.of("{\"schemaVersion\":99,\"monthlyBudgetCny\":10}"))
                .thenReturn(Optional.of("{\"schemaVersion\":1,\"monthlyBudgetCny\":-5}"))
                .thenReturn(Optional.of("{\"schemaVersion\":1,\"monthlyBudgetCny\":0}"));

        assertThat(settings.read().hasBudget()).isFalse();
        assertThat(settings.read().hasBudget()).isFalse();
        assertThat(settings.read().hasBudget()).isFalse();
        assertThat(settings.read().hasBudget()).isFalse();
    }

    /** 定价条目级 fail-closed：单价缺失/非正/模型名空白的条目跳过，其余条目保留。 */
    @Test
    @DisplayName("非法定价条目跳过，合法条目保留")
    void invalidPricingEntriesSkipped() {
        when(appSettingService.findValue(LlmBudgetSettings.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"monthlyBudgetCny\":80,"
                                        + "\"pricing\":{"
                                        + "\"glm-4.7\":{\"inputPerMillionCny\":2,\"outputPerMillionCny\":8},"
                                        + "\"bad\":{\"inputPerMillionCny\":0,\"outputPerMillionCny\":8},"
                                        + "\"half\":{\"inputPerMillionCny\":2},"
                                        + "\"\":{\"inputPerMillionCny\":2,\"outputPerMillionCny\":8}}}"));

        LlmBudgetSettings.Config config = settings.read();

        assertThat(config.hasBudget()).isTrue();
        assertThat(config.monthlyBudgetCny()).isEqualByComparingTo(new BigDecimal("80"));
        assertThat(config.pricing()).containsOnlyKeys("glm-4.7");
        assertThat(config.pricing().get("glm-4.7").inputPerMillionCny())
                .isEqualByComparingTo(new BigDecimal("2"));
        assertThat(config.pricing().get("glm-4.7").outputPerMillionCny())
                .isEqualByComparingTo(new BigDecimal("8"));
    }

    /** 写路径：序列化形状带 schemaVersion；定价空表与未设预算落 null（与读路径的清除语义对齐）。 */
    @Test
    @DisplayName("写路径序列化形状与清除语义")
    void writeSerializesStoredShape() {
        settings.write(
                new LlmBudgetSettings.Config(
                        new BigDecimal("80"),
                        Map.of(
                                "glm-4.7",
                                new LlmModelPricing(new BigDecimal("2"), new BigDecimal("8")))),
                1L);
        // 清除语义：null 预算/空定价 → JSON 两字段均为 null（第二次写入）。
        settings.write(new LlmBudgetSettings.Config(null, Map.of()), 1L);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(appSettingService, org.mockito.Mockito.times(2))
                .upsert(
                        eq(LlmBudgetSettings.SETTING_KEY),
                        value.capture(),
                        eq("月度模型预算与定价（033 预算护栏）"),
                        eq(1L));
        LlmBudgetSettings.StoredBudget stored =
                objectMapper.readValue(
                        value.getAllValues().get(0), LlmBudgetSettings.StoredBudget.class);
        assertThat(stored.schemaVersion()).isEqualTo(1);
        assertThat(stored.monthlyBudgetCny()).isEqualByComparingTo(new BigDecimal("80"));
        assertThat(stored.pricing()).containsKey("glm-4.7");
        assertThat(value.getAllValues().get(1))
                .contains("\"monthlyBudgetCny\":null")
                .contains("\"pricing\":null");
    }

    /** 写读往返：经 write 落库的 JSON 被 read 完整还原（形状同源，无双解析漂移）。 */
    @Test
    @DisplayName("写读往返一致")
    void writeReadRoundTrip() {
        String json =
                objectMapper.writeValueAsString(
                        new LlmBudgetSettings.StoredBudget(
                                1,
                                new BigDecimal("99.99"),
                                Map.of(
                                        "m1",
                                        new LlmBudgetSettings.StoredPricing(
                                                new BigDecimal("0.5"), new BigDecimal("1.5")))));
        when(appSettingService.findValue(LlmBudgetSettings.SETTING_KEY))
                .thenReturn(Optional.of(json));

        LlmBudgetSettings.Config config = settings.read();

        assertThat(config.monthlyBudgetCny()).isEqualByComparingTo(new BigDecimal("99.99"));
        assertThat(config.pricing().get("m1").inputPerMillionCny())
                .isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(config.pricing().get("m1").outputPerMillionCny())
                .isEqualByComparingTo(new BigDecimal("1.5"));
    }
}
