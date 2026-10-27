package io.github.codejinxz.yilian.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/** 安全上下文读取：只认 {@link YilianUserPrincipal}，未认证与其他认证方式一律视为未登录。 */
class SecurityUtilsTest {

    private static final YilianUserPrincipal PRINCIPAL =
            YilianUserPrincipal.of(42L, "alice", "USER", true);

    @BeforeEach
    void clearContextBeforeTest() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContextAfterTest() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsPrincipalFromSecurityContext() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                PRINCIPAL, "token", PRINCIPAL.getAuthorities()));

        assertThat(SecurityUtils.currentPrincipal()).containsSame(PRINCIPAL);
        assertThat(SecurityUtils.currentUsername()).contains("alice");
        assertThat(SecurityUtils.currentUserId()).contains(42L);
    }

    @Test
    void returnsEmptyWithoutAuthentication() {
        assertThat(SecurityUtils.currentPrincipal()).isEmpty();
        assertThat(SecurityUtils.currentUsername()).isEmpty();
        assertThat(SecurityUtils.currentUserId()).isEmpty();
    }

    @Test
    void ignoresPrincipalOfAnotherType() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "bob", "token", List.of()));

        assertThat(SecurityUtils.currentPrincipal()).isEmpty();
        assertThat(SecurityUtils.currentUsername()).isEmpty();
        assertThat(SecurityUtils.currentUserId()).isEmpty();
    }
}
