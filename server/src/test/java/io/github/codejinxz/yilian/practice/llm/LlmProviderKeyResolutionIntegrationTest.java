package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.practice.service.LlmProviderAdminService;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderCreateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmProviderView;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

/**
 * 027B 条目密钥解析链集成测试（真实 MySQL）[AC-3]： <strong>DB 密文解密 &gt; {@code YILIAN_LLM_KEY_<大写ID>} env &gt;
 * legacy</strong>—— UI 入库值生效优先于同名 env；删除入库值（目录条目删除同事务删密钥行）回退 env； 密文损坏按「密钥不存在」回退 env 不阻塞目录读；hasKey
 * =「DB 或 env 任一」； credentialsComplete 与 chatHandle 按 hasKey 新口径联动（anthropic 轨 key 可空语义保留）。
 *
 * <p>legacy 轨（目录空回退 YILIAN_LLM_* 三件套）语义不变，由 {@code LlmClientRegistryTest} 单测锁定； 密文损坏用例不断言 registry
 * 缓存句柄——缓存指纹只含正常写路径可观测的状态（目录 + updated_at）， 直接改库旁路失效信号属预期行为。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            // 同名 env 密钥（provider id it-keyprov → 连字符转下划线大写），用于验证 DB > env 优先级。
            "YILIAN_LLM_KEY_IT_KEYPROV=env-key-value-001"
        })
@ActiveProfiles("test")
class LlmProviderKeyResolutionIntegrationTest {

    /** 测试用条目 id（目录唯一，env 名映射 YILIAN_LLM_KEY_IT_KEYPROV）。 */
    private static final String PROVIDER_ID = "it-keyprov";

    /** 无同名 env 的对照条目 id（验证「DB 与 env 全缺」与 anthropic 可空语义）。 */
    private static final String PLAIN_ID = "it-keyprov-plain";

    /** UI 录入的明文密钥（只在本测试内存中出现，断言其不落明文列）。 */
    private static final String UI_KEY = "ui-secret-key-8811";

    @Autowired private LlmProviderKeys providerKeys;

    @Autowired private LlmProviderAdminService providerAdminService;

    @Autowired private LlmClientRegistry llmClients;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 每用例前后都清理目录与密钥行：恢复「空目录 + 无密钥行」基线，前清防上次失败运行残留，后清防污染其他测试类。 */
    @BeforeEach
    @AfterEach
    void cleanCatalogAndKeys() {
        jdbcTemplate.update(
                "DELETE FROM llm_provider_keys WHERE provider_id IN (?, ?)", PROVIDER_ID, PLAIN_ID);
        jdbcTemplate.update(
                "DELETE FROM app_settings WHERE `key` IN ('llm.providers', 'llm.active')");
    }

    /**
     * 以 openai-chat 协议创建测试条目（只录元数据）。
     *
     * @param providerId 目录条目 id（决定 env 索引名）
     * @return 创建后的条目视图
     */
    private LlmProviderView createOpenAiProvider(String providerId) {
        return providerAdminService.create(
                new LlmProviderCreateRequest(
                        providerId,
                        "解析链集成测试",
                        "openai-chat",
                        "http://127.0.0.1:1/v4",
                        "glm-4.7",
                        null),
                1L);
    }

    /** [AC-3] UI 入库值生效 > 同名 env：解析链直写密钥行，解析出 UI 值而非 env 值，密文列无明文。 */
    @Test
    @DisplayName("[AC-3] UI 入库值优先于同名 env 且密文列无明文")
    void ac3_uiStoredKeyWinsOverEnv() {
        createOpenAiProvider(PROVIDER_ID);
        // 管理端「UI 录入」最终落到这一步（服务层接线见 AC-4 切片）；此处直接验证解析链。
        providerKeys.upsert(PROVIDER_ID, UI_KEY);

        // 解析链：DB 密文解密在前，同名 env 存在也不生效。
        assertThat(providerKeys.resolve(PROVIDER_ID)).contains(UI_KEY);
        assertThat(providerKeys.resolveDecrypted(PROVIDER_ID)).contains(UI_KEY);

        // 密文列上无明文（加密落库红线）。
        String cipher =
                jdbcTemplate.queryForObject(
                        "SELECT secret_cipher FROM llm_provider_keys WHERE provider_id = ?",
                        String.class,
                        PROVIDER_ID);
        assertThat(cipher).isNotEqualTo(UI_KEY).doesNotContain(UI_KEY);

        // hasKey 与 last4 口径：DB 态 hasKey=true，尾 4 位来自录入留痕。
        assertThat(providerKeys.hasKey(PROVIDER_ID)).isTrue();
        assertThat(providerKeys.storedLast4(PROVIDER_ID)).contains("8811");

        // 激活后 registry 装配走新口径：hasKey=true、凭证齐备（openai 轨）、句柄可用。
        providerAdminService.activate(PROVIDER_ID, 1L);
        assertThat(llmClients.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.providerId()).isEqualTo(PROVIDER_ID);
                            assertThat(config.hasKey()).isTrue();
                            assertThat(config.credentialsComplete()).isTrue();
                        });
        assertThat(llmClients.chatHandle()).isPresent();
    }

    /** [AC-3] 删 UI 值回退 env：密钥行删除后解析回落同名 env；目录条目删除同事务联动在 AC-4 切片锁定。 */
    @Test
    @DisplayName("[AC-3] 删除入库值后回退 env")
    void ac3_deletingStoredKeyFallsBackToEnv() {
        createOpenAiProvider(PROVIDER_ID);
        providerKeys.upsert(PROVIDER_ID, UI_KEY);
        assertThat(providerKeys.resolve(PROVIDER_ID)).contains(UI_KEY);

        // 删除入库值（管理端删除条目时的同事务路径，服务层接线见 AC-4）。
        providerKeys.delete(PROVIDER_ID);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM llm_provider_keys WHERE provider_id = ?",
                                Long.class,
                                PROVIDER_ID))
                .isZero();

        // DB 轨空：回退同名 env。
        assertThat(providerKeys.resolveDecrypted(PROVIDER_ID)).isEmpty();
        assertThat(providerKeys.resolve(PROVIDER_ID)).contains("env-key-value-001");
        assertThat(providerKeys.hasKey(PROVIDER_ID)).isTrue();
        // env 态不显示 last4（无 DB 行）。
        assertThat(providerKeys.storedLast4(PROVIDER_ID)).isEmpty();
    }

    /** [AC-3] 密文损坏 fail-safe：解密失败按「密钥不存在」回退 env，不阻塞目录读。 */
    @Test
    @DisplayName("[AC-3] 密文损坏回退 env 且不阻塞目录读")
    void ac3_brokenCipherFallsBackToEnvWithoutBlocking() {
        createOpenAiProvider(PROVIDER_ID);
        providerKeys.upsert(PROVIDER_ID, UI_KEY);
        assertThat(providerKeys.resolve(PROVIDER_ID)).contains(UI_KEY);

        // 直接改坏密文列（模拟主密钥被换/密文被篡改）。
        jdbcTemplate.update(
                "UPDATE llm_provider_keys SET secret_cipher = 'not-a-valid-cipher'"
                        + " WHERE provider_id = ?",
                PROVIDER_ID);

        // 解析回落 env（视为不存在），目录读不被打挂；hasKey 口径与生效密钥一致（env 态生效）。
        assertThat(providerKeys.resolveDecrypted(PROVIDER_ID)).isEmpty();
        assertThat(providerKeys.resolve(PROVIDER_ID)).contains("env-key-value-001");
        assertThat(providerKeys.hasKey(PROVIDER_ID)).isTrue();
    }

    /** [AC-3] DB 与 env 全缺（openai 轨）：hasKey=false、凭证不齐（视图在、句柄缺）。 */
    @Test
    @DisplayName("[AC-3] 无任何密钥时 openai 轨凭证不齐")
    void ac3_missingEverywhereOpenAiTrackIncomplete() {
        // PLAIN_ID 无同名 env（测试上下文只设了 IT_KEYPROV），也不录 DB 行。
        createOpenAiProvider(PLAIN_ID);
        providerAdminService.activate(PLAIN_ID, 1L);
        assertThat(providerKeys.resolve(PLAIN_ID)).isEqualTo(Optional.empty());
        assertThat(providerKeys.hasKey(PLAIN_ID)).isFalse();
        assertThat(llmClients.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.providerId()).isEqualTo(PLAIN_ID);
                            assertThat(config.hasKey()).isFalse();
                            assertThat(config.credentialsComplete()).isFalse();
                        });
        assertThat(llmClients.chatHandle()).isEmpty();
    }

    /** [AC-3] anthropic 轨 key 可空语义保留：无 DB 无 env 时凭证仍齐备（端点必填、key 可空）。 */
    @Test
    @DisplayName("[AC-3] anthropic 轨无 key 凭证齐备语义保留")
    void ac3_anthropicTrackKeyOptionalSemanticsPreserved() {
        providerAdminService.create(
                new LlmProviderCreateRequest(
                        PLAIN_ID,
                        "解析链集成测试（anthropic）",
                        "anthropic-messages",
                        "http://127.0.0.1:1/gateway/zcode",
                        "glm-5.3",
                        null),
                1L);
        providerAdminService.activate(PLAIN_ID, 1L);
        assertThat(providerKeys.hasKey(PLAIN_ID)).isFalse();
        assertThat(llmClients.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.hasKey()).isFalse();
                            assertThat(config.credentialsComplete()).isTrue();
                        });
        assertThat(llmClients.chatHandle()).isPresent();
    }
}
