package io.github.codejinxz.yilian.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.system.bootstrap.BootstrapAdminProperties;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.UserRole;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

/** bootstrap 管理员的校验、创建与修复行为；不启动 Spring 容器，直接驱动服务实现。 */
class BootstrapAdminServiceImplTest {

    private final AppUserDao appUserDao = mock(AppUserDao.class);

    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository =
            mock(FindByIndexNameSessionRepository.class);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    void prodRejectsDefaultPassword() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> service(environment, "change-me-now", false).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YILIAN_BOOTSTRAP_ADMIN_PASSWORD");

        verify(appUserDao, never()).save(any());
    }

    @Test
    void prodRejectsMissingPassword() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> service(environment, " ", false).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YILIAN_BOOTSTRAP_ADMIN_PASSWORD");

        verify(appUserDao, never()).save(any());
    }

    @Test
    void prodRejectsWeakPassword() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> service(environment, "short", false).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12");

        verify(appUserDao, never()).save(any());
    }

    @Test
    void localCreatesBcryptHashedAdmin() {
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.empty());

        service(new MockEnvironment(), "change-me-now", false).initialize();

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserDao).save(captor.capture());
        AppUser saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("admin");
        assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getPasswordHash()).isNotEqualTo("change-me-now");
        assertThat(encoder.matches("change-me-now", saved.getPasswordHash())).isTrue();
    }

    @Test
    void resetsPasswordWhenOneShotSwitchIsEnabled() {
        AppUser existing = AppUser.newAdmin("admin", encoder.encode("old-password"), Instant.now());
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.of(existing));

        service(new MockEnvironment(), "new-password", true).initialize();

        assertThat(encoder.matches("new-password", existing.getPasswordHash())).isTrue();
        assertThat(encoder.matches("old-password", existing.getPasswordHash())).isFalse();
        verify(appUserDao).save(existing);
    }

    @Test
    void repairsDisabledNonAdmin() {
        AppUser existing = AppUser.newAdmin("admin", encoder.encode("old-password"), Instant.now());
        ReflectionTestUtils.setField(existing, "enabled", false);
        ReflectionTestUtils.setField(existing, "role", UserRole.USER);
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.of(existing));

        service(new MockEnvironment(), "new-password", false).initialize();

        assertThat(existing.isEnabled()).isTrue();
        assertThat(existing.getRole()).isEqualTo(UserRole.ADMIN);
        verify(appUserDao).save(existing);
    }

    @Test
    void leavesHealthyAdminUntouched() {
        AppUser existing = AppUser.newAdmin("admin", encoder.encode("old-password"), Instant.now());
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.of(existing));

        service(new MockEnvironment(), "new-password", false).initialize();

        assertThat(encoder.matches("old-password", existing.getPasswordHash())).isTrue();
        verify(appUserDao, never()).save(any());
    }

    private BootstrapAdminServiceImpl service(
            MockEnvironment environment, String password, boolean resetPasswordOnStartup) {
        return new BootstrapAdminServiceImpl(
                new BootstrapAdminProperties(true, "admin", password, resetPasswordOnStartup),
                appUserDao,
                encoder,
                environment,
                sessionRepository);
    }
}
