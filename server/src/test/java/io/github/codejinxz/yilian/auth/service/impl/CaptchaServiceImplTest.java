package io.github.codejinxz.yilian.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Cache;

import io.github.codejinxz.yilian.auth.vo.CaptchaChallenge;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 图形验证码服务单测 [AC-4]：挑战结构、一次性消费、错误/过期拒绝、开关放行。
 *
 * <p>答案存私有 Caffeine，测试经反射读取以拿到明文答案断言「对码放行」； 行为锁在这里而非 HTTP 层，验证码端点的公开性由 AuthFlowIntegrationTest 覆盖。
 */
class CaptchaServiceImplTest {

    private CaptchaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CaptchaServiceImpl(true);
    }

    /** 读取私有答案缓存（同包单测专用）。 */
    @SuppressWarnings("unchecked")
    private Cache<String, String> answers() {
        return (Cache<String, String>) ReflectionTestUtils.getField(service, "answers");
    }

    /** [AC-4] 生成挑战：id 与 base64 图片非空，答案已入缓存。 */
    @Test
    @DisplayName("[AC-4] 生成挑战并写入答案缓存")
    void ac4_generateReturnsBase64Challenge() {
        CaptchaChallenge challenge = service.generate();

        assertThat(challenge.captchaId()).isNotBlank();
        assertThat(challenge.imageBase64()).isNotBlank();
        assertThat(answers().getIfPresent(challenge.captchaId())).isNotBlank();
    }

    /** [AC-4] 对码放行且一次性消费：大小写不敏感，第二次校验同码被拒。 */
    @Test
    @DisplayName("[AC-4] 对码放行并消费，重放同码被拒")
    void ac4_correctCodePassesAndConsumedOnce() {
        CaptchaChallenge challenge = service.generate();
        String code = answers().getIfPresent(challenge.captchaId());

        assertThatCode(
                        () ->
                                service.verifyOrThrow(
                                        challenge.captchaId(),
                                        code.toUpperCase(java.util.Locale.ROOT)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> service.verifyOrThrow(challenge.captchaId(), code))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
    }

    /** [AC-4] 错码、未知 id、空白字段一律 40000。 */
    @Test
    @DisplayName("[AC-4] 错码/未知 id/空白字段拒绝")
    void ac4_wrongOrUnknownOrBlankRejected() {
        CaptchaChallenge challenge = service.generate();

        assertThatThrownBy(() -> service.verifyOrThrow(challenge.captchaId(), "0000"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verifyOrThrow("no-such-id", "abcd"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verifyOrThrow(null, "abcd"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.verifyOrThrow(challenge.captchaId(), " "))
                .isInstanceOf(BusinessException.class);
    }

    /** [AC-4] 开关关闭时校验直接放行（本地联调/自动化测试路径）。 */
    @Test
    @DisplayName("[AC-4] 开关关闭直接放行")
    void ac4_disabledAlwaysPasses() {
        CaptchaServiceImpl disabled = new CaptchaServiceImpl(false);

        assertThatCode(() -> disabled.verifyOrThrow(null, null)).doesNotThrowAnyException();
    }
}
