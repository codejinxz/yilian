package io.github.codejinxz.yilian.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.dao.InviteCodeDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.InviteCode;
import io.github.codejinxz.yilian.system.entity.UserRole;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeAdminView;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 账号用例实现的管理侧分支纯单测（不启动 Spring 容器，Mockito 直驱，先例同 {@code BootstrapAdminServiceImplTest}）：自己停自己
 * [AC-3]、自己降自己 [AC-3] 被拒， 邀请码生成 [AC-4] 只落哈希与前缀、列表回填使用者用户名。
 */
class UserAccountServiceImplTest {

    private final AppUserDao appUserDao = mock(AppUserDao.class);

    private final InviteCodeDao inviteCodeDao = mock(InviteCodeDao.class);

    private final UserAccountServiceImpl service =
            new UserAccountServiceImpl(
                    appUserDao, inviteCodeDao, new BCryptPasswordEncoder(4), false);

    /** [AC-3] 自己停自己：业务失败（40900），不落库。 */
    @Test
    void adminSetStatusRejectsSelfDisable_ac3() {
        when(appUserDao.findById(1L)).thenReturn(Optional.of(user(1L, "admin")));

        assertThatThrownBy(() -> service.adminSetStatus(1L, 1L, false, "手滑"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能停用自己")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT);

        verify(appUserDao, never()).save(any());
    }

    /** [AC-3] 自己给自己降级：业务失败（40900），不落库。 */
    @Test
    void adminSetRoleRejectsSelfDemotion_ac3() {
        when(appUserDao.findById(1L)).thenReturn(Optional.of(user(1L, "admin")));

        assertThatThrownBy(() -> service.adminSetRole(1L, 1L, "USER", "手滑"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能变更自己的角色")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT);

        verify(appUserDao, never()).save(any());
    }

    /** [AC-3] 非法角色字符串收敛为参数校验失败（40000），不触达用户查询。 */
    @Test
    void adminSetRoleRejectsUnknownRoleValue_ac3() {
        assertThatThrownBy(() -> service.adminSetRole(1L, 3L, "OWNER", "试试"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("role 不受支持");

        verify(appUserDao, never()).findById(any());
    }

    /** [AC-4] 生成邀请码：明文形如 YL-XXXX-XXXX，库里只落 SHA-256 与 7 位前缀，createdBy 记管理员。 */
    @Test
    void adminCreateInviteStoresHashAndPrefixOnly_ac4() throws Exception {
        when(inviteCodeDao.findByCodeHash(any())).thenReturn(Optional.empty());
        when(inviteCodeDao.save(any(InviteCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.adminCreateInvite(1L);

        assertThat(created.code()).matches("YL-[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(created.codePrefix()).isEqualTo(created.code().substring(0, 7));
        assertThat(created.createdAt()).isNotNull();

        ArgumentCaptor<InviteCode> captor = ArgumentCaptor.forClass(InviteCode.class);
        verify(inviteCodeDao).save(captor.capture());
        InviteCode saved = captor.getValue();
        assertThat(saved.getCodeHash()).isEqualTo(sha256(created.code()));
        assertThat(saved.getCodePrefix()).isEqualTo(created.codePrefix());
        assertThat(saved.getUsedBy()).isNull();
    }

    /** [AC-4] 生成冲突（哈希已存在）时重摇随机码，最终仍返回可用明文。 */
    @Test
    void adminCreateInviteRetriesOnHashCollision_ac4() {
        when(inviteCodeDao.findByCodeHash(any()))
                .thenReturn(
                        Optional.of(
                                InviteCode.newSingleUse("x", "YL-OLD", null, 1L, Instant.now())))
                .thenReturn(Optional.empty());
        when(inviteCodeDao.save(any(InviteCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.adminCreateInvite(1L);

        assertThat(created.code()).matches("YL-[A-Z2-9]{4}-[A-Z2-9]{4}");
        verify(inviteCodeDao).save(any(InviteCode.class));
    }

    /** [AC-4] 邀请码列表：未用的 usedBy 为 null；已用的回填使用者用户名。 */
    @Test
    void adminListInvitesResolvesUsedByUsername_ac4() {
        Instant now = Instant.parse("2026-10-31T03:00:00Z");
        InviteCode unused = InviteCode.newSingleUse("hash-a", "YL-AAAA", null, 1L, now);
        InviteCode used = InviteCode.newSingleUse("hash-b", "YL-BBBB", null, 1L, now);
        used.markUsed(7L, now);
        when(inviteCodeDao.findRecent(50)).thenReturn(List.of(used, unused));

        AppUser learner = user(7L, "learner");
        when(appUserDao.findByIds(List.of(7L))).thenReturn(List.of(learner));

        List<InviteCodeAdminView> views = service.adminListInvites();

        assertThat(views).hasSize(2);
        assertThat(views.get(0).codePrefix()).isEqualTo("YL-BBBB");
        assertThat(views.get(0).used()).isTrue();
        assertThat(views.get(0).usedBy()).isEqualTo("learner");
        assertThat(views.get(0).createdAt()).isEqualTo(now);
        assertThat(views.get(1).used()).isFalse();
        assertThat(views.get(1).usedBy()).isNull();
    }

    /** [AC-4] 列表的使用者已不在（如已注销）时 usedBy 回退为 null，不抛错。 */
    @Test
    void adminListInvitesToleratesMissingUsedByUser_ac4() {
        InviteCode used = InviteCode.newSingleUse("hash-b", "YL-BBBB", null, 1L, Instant.now());
        used.markUsed(99L, Instant.now());
        when(inviteCodeDao.findRecent(50)).thenReturn(List.of(used));
        when(appUserDao.findByIds(anyList())).thenReturn(List.of());

        List<InviteCodeAdminView> views = service.adminListInvites();

        assertThat(views.get(0).used()).isTrue();
        assertThat(views.get(0).usedBy()).isNull();
    }

    /** [AC-5] 降级最后一个管理员被拒：行锁计数为 1 时返回 40900 且不落库。 */
    @Test
    void adminSetRoleRejectsDemotingLastAdmin_ac5() {
        when(appUserDao.findById(3L)).thenReturn(Optional.of(adminUser(3L, "deputy")));
        when(appUserDao.countActiveAdminsForUpdate()).thenReturn(1L);

        assertThatThrownBy(() -> service.adminSetRole(1L, 3L, "USER", "降级"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能降级最后一个管理员")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT);

        verify(appUserDao, never()).save(any());
    }

    /** [AC-5] 存在多个管理员时降级放行：锁计数 ≥2，正常保存 USER 角色。 */
    @Test
    void adminSetRoleAllowsDemotingWhenOtherAdminsRemain_ac5() {
        when(appUserDao.findById(3L)).thenReturn(Optional.of(adminUser(3L, "deputy")));
        when(appUserDao.countActiveAdminsForUpdate()).thenReturn(2L);
        when(appUserDao.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.adminSetRole(1L, 3L, "USER", "降级");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserDao).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.USER);
    }

    /** [AC-5] 停用最后一个管理员同样走行锁计数并拒绝（同一竞态的另一半）。 */
    @Test
    void adminSetStatusRejectsDisablingLastAdmin_ac5() {
        when(appUserDao.findById(3L)).thenReturn(Optional.of(adminUser(3L, "deputy")));
        when(appUserDao.countActiveAdminsForUpdate()).thenReturn(1L);

        assertThatThrownBy(() -> service.adminSetStatus(1L, 3L, false, "违规"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能停用最后一个管理员")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT);

        verify(appUserDao, never()).save(any());
    }

    /** [AC-5] 升级/平调不触发行锁计数：USER→ADMIN 不发起 FOR UPDATE。 */
    @Test
    void adminSetRolePromotionSkipsAdminCountLock_ac5() {
        when(appUserDao.findById(3L)).thenReturn(Optional.of(user(3L, "learner")));
        when(appUserDao.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.adminSetRole(1L, 3L, "ADMIN", "接手运营");

        verify(appUserDao, never()).countActiveAdminsForUpdate();
    }

    /**
     * 构造带 id 的管理员账号实体。
     *
     * @param id 账号 id
     * @param username 登录名
     * @return 管理员实体
     */
    private static AppUser adminUser(long id, String username) {
        AppUser user = user(id, username);
        user.changeRole(UserRole.ADMIN);
        return user;
    }

    /**
     * 构造带 id 的账号实体。
     *
     * @param id 账号 id
     * @param username 登录名
     * @return 账号实体
     */
    private static AppUser user(long id, String username) {
        AppUser user = AppUser.newUser(username + "@example.com", username, "hash", Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /**
     * 计算规范化邀请码的 SHA-256，口径与实现内一致（大写十六进制）。
     *
     * @param code 邀请码明文
     * @return SHA-256 十六进制
     */
    private static String sha256(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
