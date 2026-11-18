package io.github.codejinxz.yilian.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.auth.dto.LoginRequest;
import io.github.codejinxz.yilian.auth.service.CaptchaService;
import io.github.codejinxz.yilian.auth.service.JwtTokenService;
import io.github.codejinxz.yilian.auth.service.TotpService;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.SecretCipher;
import io.github.codejinxz.yilian.common.session.RefreshSessionGuard;
import io.github.codejinxz.yilian.system.service.TotpAccountService;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 登录时序侧信道防护（review-verify-2 03#01，AC-1）的单测：不启动 Spring，直接驱动服务实现， 用 mock 编码器验证「两分支都调 BCrypt」的分支语义。
 */
class AuthServiceImplTest {

    private final UserAccountService userAccountService = mock(UserAccountService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<? extends Session> refreshSessions =
            mock(FindByIndexNameSessionRepository.class);

    private final AuthServiceImpl service =
            new AuthServiceImpl(
                    userAccountService,
                    passwordEncoder,
                    mock(CsrfTokenRepository.class),
                    mock(JwtTokenService.class),
                    refreshSessions,
                    mock(CaptchaService.class),
                    new RefreshSessionGuard(),
                    mock(TotpService.class),
                    mock(TotpAccountService.class),
                    mock(SecretCipher.class),
                    mock(io.github.codejinxz.yilian.practice.service.PracticeService.class),
                    30);

    /** [AC-1] 用户名不存在时必须对固定假哈希执行一次 BCrypt matches 后才抛 40101，耗时与存在分支对齐。 */
    @Test
    void loginRunsFakeBcryptWhenUserMissing() {
        when(userAccountService.findByLogin("ghost")).thenReturn(Optional.empty());
        LoginRequest request = request("ghost", "whatever-password");

        assertThatThrownBy(() -> service.login(request, req(), res()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.AUTH_INVALID_CREDENTIALS));

        // 假哈希比对恰好一次：分支语义即「不存在路径也付出一次完整 BCrypt」。
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, times(1)).matches(anyString(), hashCaptor.capture());
        assertThat(hashCaptor.getValue())
                .isEqualTo("$2a$10$eRMlRPfhaLqguHHHIFhyQuKqtNp4ScBstCepsVwf0G20u1qsR.UIq");
    }

    /** [AC-1] 账号停用与软删同样落入填充分支：可见性过滤后为空 = 不存在，同样执行假 BCrypt。 */
    @Test
    void loginRunsFakeBcryptWhenUserDisabledOrDeleted() {
        when(userAccountService.findByLogin("paused"))
                .thenReturn(Optional.of(credentials("paused", false, true)));
        LoginRequest request = request("paused", "whatever-password");

        assertThatThrownBy(() -> service.login(request, req(), res()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.AUTH_INVALID_CREDENTIALS));
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    /** [AC-1] 存在且启用：比对真实哈希（而非假哈希），错口令同样 40101。 */
    @Test
    void loginChecksRealHashWhenUserPresent() {
        UserCredentials user = credentials("alice", true, false);
        when(userAccountService.findByLogin("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", user.passwordHash())).thenReturn(false);
        LoginRequest request = request("alice", "wrong-password");

        assertThatThrownBy(() -> service.login(request, req(), res()))
                .isInstanceOf(BusinessException.class);

        verify(passwordEncoder, times(1)).matches(eq("wrong-password"), eq(user.passwordHash()));
    }

    private static LoginRequest request(String username, String password) {
        return new LoginRequest(username, password, null, null);
    }

    private static UserCredentials credentials(String username, boolean enabled, boolean deleted) {
        return new UserCredentials(
                2L,
                username + "@example.com",
                username,
                "$2a$10$storedhashplaceholderstoredhashplaceholder12",
                io.github.codejinxz.yilian.system.entity.UserRole.USER,
                enabled,
                true,
                false,
                "Asia/Shanghai",
                3,
                new BigDecimal("0.900"),
                deleted,
                java.time.Instant.EPOCH);
    }

    private static HttpServletRequest req() {
        return mock(HttpServletRequest.class);
    }

    private static HttpServletResponse res() {
        return mock(HttpServletResponse.class);
    }
}
