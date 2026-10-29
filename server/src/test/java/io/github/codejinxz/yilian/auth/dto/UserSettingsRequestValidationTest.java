package io.github.codejinxz.yilian.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

/**
 * UserSettingsRequest 声明式校验单测 [AC-3]：范围/长度/精度/模式类注解首道拦截， 携带字段级中文明细；合法值零违规（集合成员精确值与 IANA
 * 校验在服务层，纵深防御）。
 */
class UserSettingsRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /** [AC-3] 合法设置零违规（合法时区与集合成员由服务层校验，此处只证注解层不误伤）。 */
    @Test
    @DisplayName("[AC-3] 合法值零违规")
    void ac3_validRequestHasNoViolations() {
        UserSettingsRequest request =
                new UserSettingsRequest(
                        "Asia/Shanghai", 3, "TEM8", new BigDecimal("0.900"), "paper", Boolean.TRUE);

        assertThat(validator.validate(request)).isEmpty();
    }

    /** [AC-3] 越界 desiredRetention 触发首道 400 明细（下限与精度）。 */
    @Test
    @DisplayName("[AC-3] desiredRetention 越界与精度违规")
    void ac3_desiredRetentionBoundsAndDigits() {
        UserSettingsRequest tooLow =
                new UserSettingsRequest(null, null, null, new BigDecimal("0.500"), null, null);
        Set<ConstraintViolation<UserSettingsRequest>> violations = validator.validate(tooLow);
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("期望保留率最小为 0.800");

        UserSettingsRequest tooPrecise =
                new UserSettingsRequest(null, null, null, new BigDecimal("0.9123"), null, null);
        assertThat(validator.validate(tooPrecise))
                .extracting(ConstraintViolation::getMessage)
                .contains("期望保留率最多 3 位小数");
    }

    /** [AC-3] 非法 theme/ratingStandard/dailyGoal/timezone 分别命中对应注解明细。 */
    @Test
    @DisplayName("[AC-3] theme/ratingStandard/dailyGoal/timezone 违规明细")
    void ac3_patternAndSizeViolations() {
        UserSettingsRequest request =
                new UserSettingsRequest(
                        "x".repeat(65), 9, "GRE", new BigDecimal("0.900"), "dark", null);

        Set<ConstraintViolation<UserSettingsRequest>> violations = validator.validate(request);
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains(
                        "时区标识最长 64 个字符",
                        "每日目标最大为 5",
                        "评分标准只支持 TEM8、CATTI 或 IELTS",
                        "主题只支持 paper 或 night");
    }
}
