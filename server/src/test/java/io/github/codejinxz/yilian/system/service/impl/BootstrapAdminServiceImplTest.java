package io.github.codejinxz.yilian.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.session.RefreshSessionGuard;
import io.github.codejinxz.yilian.system.bootstrap.BootstrapAdminProperties;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.UserRole;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/** bootstrap 管理员的校验、创建与修复行为；不启动 Spring 容器，直接驱动服务实现。 */
class BootstrapAdminServiceImplTest {

    private final AppUserDao appUserDao = mock(AppUserDao.class);

    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository =
            mock(FindByIndexNameSessionRepository.class);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    /** 每用例独立临时目录：一次性开关标记文件的落点，避免用例间串扰。 */
    @TempDir Path markerDir;

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

    /**
     * [AC-2] 别名部署不再静默绕过：profile 不叫 prod 但 {@code YILIAN_PROD_MARKER=true} 时， 默认口令同样 fail-fast（容器里
     * profile=container 的场景）。
     */
    @Test
    void prodMarkerForcesFailFastOnDefaultPasswordWithoutProdProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("container");
        environment.setProperty("YILIAN_PROD_MARKER", "true");

        assertThatThrownBy(() -> service(environment, "change-me-now", false).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YILIAN_BOOTSTRAP_ADMIN_PASSWORD");

        verify(appUserDao, never()).save(any());
    }

    /** [AC-2] 无 prod profile 且无标记：维持 local 语义，默认口令放行（仅告警）。 */
    @Test
    void noMarkerAndNoProdProfileKeepsLocalBehavior() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.empty());

        service(environment, "change-me-now", false).initialize();

        verify(appUserDao).save(any());
    }

    /** [AC-2] 别名 profile（production）名字含 prod：即使无标记也按 prod 加固。 */
    @Test
    void prodAliasProfileForcesFailFast() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> service(environment, "change-me-now", false).initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YILIAN_BOOTSTRAP_ADMIN_PASSWORD");

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
        // 改密必须同时吊销 refresh 会话（更早的用例缺口：此前只断言口令被覆盖）。
        verify(sessionRepository).findByPrincipalName("admin");
    }

    /** [AC-3] 第一次启动：重置生效且标记文件记录配置口令哈希（BCrypt），文件不含明文。 */
    @Test
    void resetWritesMarkerFileWithoutPlaintext() throws Exception {
        AppUser existing = AppUser.newAdmin("admin", encoder.encode("old-password"), Instant.now());
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.of(existing));

        service(new MockEnvironment(), "recovery-password", true).initialize();

        Path marker = markerDir.resolve("reset.marker");
        assertThat(marker).exists();
        String content = Files.readString(marker).trim();
        assertThat(content).doesNotContain("recovery-password");
        assertThat(encoder.matches("recovery-password", content)).isTrue();
    }

    /**
     * [AC-3] 第二次启动（开关忘关）：标记命中即跳过——管理员事后改密（当前口令 ≠ 配置值） 也不会被覆盖回配置口令、不再重复吊销会话 （review-verify-2 03#05
     * 的主场景）。
     */
    @Test
    void secondStartupSkipsResetAndKeepsAdminChangedPassword() throws Exception {
        // 第一次：重置生效。
        AppUser first = AppUser.newAdmin("admin", encoder.encode("old-password"), Instant.now());
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.of(first));
        service(new MockEnvironment(), "recovery-password", true).initialize();
        // 管理员事后自助改密。
        first.changePassword(encoder.encode("admins-own-password"), Instant.now());

        // 第二次启动：同一配置值 + 标记已存在 → 跳过。
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.of(first));
        service(new MockEnvironment(), "recovery-password", true).initialize();

        assertThat(encoder.matches("admins-own-password", first.getPasswordHash())).isTrue();
        assertThat(encoder.matches("recovery-password", first.getPasswordHash())).isFalse();
        // 只在第一次吊销过；第二次不再吊销（findByPrincipalName 仅第一次调用）。
        verify(sessionRepository).findByPrincipalName("admin");
    }

    /** [AC-3] 配置口令换成新值：与标记不匹配，允许再次执行恢复性重置并更新标记。 */
    @Test
    void resetAppliesAgainForNewConfiguredPassword() throws Exception {
        AppUser existing =
                AppUser.newAdmin("admin", encoder.encode("same-password"), Instant.now());
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.of(existing));
        service(new MockEnvironment(), "first-recovery", true).initialize();

        service(new MockEnvironment(), "second-recovery", true).initialize();

        assertThat(encoder.matches("second-recovery", existing.getPasswordHash())).isTrue();
        verify(sessionRepository, org.mockito.Mockito.times(2)).findByPrincipalName("admin");
        Path marker = markerDir.resolve("reset.marker");
        assertThat(encoder.matches("second-recovery", Files.readString(marker).trim())).isTrue();
    }

    /** [AC-3] 新建账号同样落标记：第二次重启不会对新建管理员重复执行「重置」并吊销会话。 */
    @Test
    void creationWritesMarkerSoSecondStartupSkips() throws Exception {
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.empty());
        service(new MockEnvironment(), "change-me-now", true).initialize();

        AppUser created = AppUser.newAdmin("admin", encoder.encode("x"), Instant.now());
        when(appUserDao.findByUsername("admin")).thenReturn(Optional.of(created));
        service(new MockEnvironment(), "change-me-now", true).initialize();

        assertThat(markerDir.resolve("reset.marker")).exists();
        // 第二次启动未触发重置路径的会话吊销（仅有 save 创建那一次）。
        verify(appUserDao, org.mockito.Mockito.times(1)).save(any());
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
                new BootstrapAdminProperties(
                        true,
                        "admin",
                        password,
                        resetPasswordOnStartup,
                        markerDir.resolve("reset.marker").toString()),
                appUserDao,
                encoder,
                environment,
                sessionRepository,
                new RefreshSessionGuard());
    }
}
