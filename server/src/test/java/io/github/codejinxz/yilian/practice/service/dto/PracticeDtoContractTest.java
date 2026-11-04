package io.github.codejinxz.yilian.practice.service.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.practice.service.dto.AnswerPayload.Slot;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand.Kind;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand.Mode;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * practice 查询/命令 DTO 的契约单测 [AC-5]：retryOfId 长度上限对齐 attempts.retry_of_id 列宽（CHAR(36)）；excludeIds
 * 归一化截断 ≤100 的静默语义。
 */
class PracticeDtoContractTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /** [AC-5] retryOfId ≤36：超长命中 400 明细，36 字内不误伤。 */
    @Test
    @DisplayName("[AC-5] retryOfId 长度上限 36")
    void ac5_retryOfIdSizeCappedAt36() {
        AttemptCommand oversized =
                new AttemptCommand(
                        "3f0e7a1e-8b1c-4a2d-9c3e-5f6a7b8c9d0e",
                        "01JPUBLIS000000000000000000C",
                        "1001",
                        Mode.CLOZE,
                        Kind.RETRY,
                        "x".repeat(37),
                        new AnswerPayload(1, "CLOZE", List.of(new Slot(0, "answer")), null),
                        0,
                        1000,
                        null);
        Set<ConstraintViolation<AttemptCommand>> violations = validator.validate(oversized);
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("retryOfId 超出长度");

        AttemptCommand withinLimit =
                new AttemptCommand(
                        "3f0e7a1e-8b1c-4a2d-9c3e-5f6a7b8c9d0e",
                        "01JPUBLIS0000000000000000C",
                        "1001",
                        Mode.CLOZE,
                        Kind.RETRY,
                        "x".repeat(36),
                        new AnswerPayload(1, "CLOZE", List.of(new Slot(0, "answer")), null),
                        0,
                        1000,
                        null);
        assertThat(validator.validate(withinLimit)).isEmpty();
    }

    /** [AC-5] excludeIds 归一化截断 ≤100：保序取先出现的 100 个，超出静默丢弃（不报错）。 */
    @Test
    @DisplayName("[AC-5] excludeIds 截断 ≤100")
    void ac5_excludeIdsCappedAt100() {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            ids.add("01JEXCLUDE%04d".formatted(index));
        }
        NextQuery capped = NextQuery.normalize(null, null, null, null, null, String.join(",", ids));
        assertThat(capped.excludeCount()).isEqualTo(100);
        assertThat(capped.excludes("01JEXCLUDE0000")).isTrue();
        assertThat(capped.excludes("01JEXCLUDE0099")).isTrue();
        assertThat(capped.excludes("01JEXCLUDE0100")).isFalse();

        // 旧单题参数优先占位：excludeSentenceId 与 excludeIds 合并后同样受上限约束。
        NextQuery merged =
                NextQuery.normalize(
                        null,
                        null,
                        null,
                        null,
                        "01JFIRST0000000000000000000X",
                        String.join(",", ids));
        assertThat(merged.excludeCount()).isEqualTo(100);
        assertThat(merged.excludes("01JFIRST0000000000000000000X")).isTrue();
        assertThat(merged.excludes("01JEXCLUDE0098")).isTrue();
        assertThat(merged.excludes("01JEXCLUDE0099")).isFalse();
    }
}
