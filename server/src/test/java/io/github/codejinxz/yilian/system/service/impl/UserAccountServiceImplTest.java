package io.github.codejinxz.yilian.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.session.RefreshSessionGuard;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.dao.InviteCodeDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.InviteCode;
import io.github.codejinxz.yilian.system.entity.UserRole;
import io.github.codejinxz.yilian.system.service.vo.AdminPasswordResetView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeAdminView;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
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

    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<? extends Session> refreshSessions =
            mock(FindByIndexNameSessionRepository.class);

    private final UserAccountServiceImpl service =
            new UserAccountServiceImpl(
                    appUserDao,
                    inviteCodeDao,
                    new BCryptPasswordEncoder(4),
                    false,
                    refreshSessions,
                    new RefreshSessionGuard());

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

    /** [AC-3] 停用账号即按用户名吊销全部 refresh 会话（016 P1·03#01：重新启用前旧 cookie 不得复活）。 */
    @Test
    void adminSetStatusDisableRevokesRefreshSessions_ac3() {
        AppUser target = user(2L, "learner");
        when(appUserDao.findById(2L)).thenReturn(Optional.of(target));
        when(appUserDao.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.adminSetStatus(1L, 2L, false, "违规停用");

        verify(refreshSessions).findByPrincipalName("learner");
        verify(appUserDao).save(any(AppUser.class));
    }

    /** [AC-3] 恢复（enable）不触发吊销：会话在停用时已删除，重复吊销无意义。 */
    @Test
    void adminSetStatusEnableDoesNotRevokeSessions_ac3() {
        AppUser target = user(2L, "learner");
        when(appUserDao.findById(2L)).thenReturn(Optional.of(target));
        when(appUserDao.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.adminSetStatus(1L, 2L, true, null);

        verify(refreshSessions, never())
                .findByPrincipalName(org.mockito.ArgumentMatchers.anyString());
    }

    /** [AC-5] 用户检索 keyword 做 LIKE 转义：用户输入的 %/_ 是字面量（015 修复轮 minor #5）。 */
    @Test
    void adminSearchEscapesLikeKeyword_ac5() {
        when(appUserDao.search(
                        org.mockito.ArgumentMatchers.eq("100\\%\\_完\\\\成"), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.adminSearch(" 100%_完\\成 ", null, null, 1, 20);

        verify(appUserDao)
                .search(org.mockito.ArgumentMatchers.eq("100\\%\\_完\\\\成"), any(), any(), any());
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

    /** [AC-3] ADMIN→USER 降级即按用户名吊销全部 refresh 会话（016 修复轮 2，与停用同款）：降级后泄露的旧 cookie 不得静默续期。 */
    @Test
    void adminSetRoleDemotionRevokesRefreshSessions_ac3() {
        when(appUserDao.findById(3L)).thenReturn(Optional.of(adminUser(3L, "deputy")));
        when(appUserDao.countActiveAdminsForUpdate()).thenReturn(2L);
        when(appUserDao.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.adminSetRole(1L, 3L, "USER", "降级");

        verify(refreshSessions).findByPrincipalName("deputy");
        verify(appUserDao).save(any(AppUser.class));
    }

    /** [AC-3] 平调/升级不吊销：USER→ADMIN 不收缩权限，不发起会话删除。 */
    @Test
    void adminSetRolePromotionDoesNotRevokeSessions_ac3() {
        when(appUserDao.findById(3L)).thenReturn(Optional.of(user(3L, "learner")));
        when(appUserDao.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.adminSetRole(1L, 3L, "ADMIN", "接手运营");

        verify(refreshSessions, never())
                .findByPrincipalName(org.mockito.ArgumentMatchers.anyString());
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

    /** [AC-6a] 重置成功：临时口令 12 位去混淆字母表、只落 BCrypt、强制改密标记复位、会话全吊销。 */
    @Test
    void adminResetPasswordIssuesOneTimePasswordAndForcesChange_ac6a() {
        AppUser target = user(3L, "learner");
        when(appUserDao.findById(3L)).thenReturn(Optional.of(target));
        when(appUserDao.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminPasswordResetView view = service.adminResetPassword(1L, 3L, "用户忘记密码");

        assertThat(view.userId()).isEqualTo("3");
        assertThat(view.username()).isEqualTo("learner");
        // 与邀请码同风格：去混淆大写字母表（无 I/L/O/0/1），12 位。
        assertThat(view.temporaryPassword()).matches("[A-HJ-NP-Z2-9]{12}");
        // 临时口令可登录（哈希落库），且目标回到「首次登录需改密」（40303 拦截业务接口）。
        assertThat(target.isPasswordChanged()).isFalse();
        assertThat(target.getPasswordHash()).isNotEqualTo(view.temporaryPassword());
        verify(refreshSessions).findByPrincipalName("learner");
    }

    /** [AC-6a] 自己重置自己被拒（40300，权限边界），不落库不吊销。 */
    @Test
    void adminResetPasswordRejectsSelf_ac6a() {
        when(appUserDao.findById(1L)).thenReturn(Optional.of(adminUser(1L, "admin")));

        assertThatThrownBy(() -> service.adminResetPassword(1L, 1L, "手滑"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能重置自己的密码")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.ACCESS_DENIED);

        verify(appUserDao, never()).save(any());
        verify(refreshSessions, never()).findByPrincipalName(any());
    }

    /** [AC-6a] 目标已软删：40400（与启停/改角色的「用户已删除」口径一致）。 */
    @Test
    void adminResetPasswordRejectsDeletedUser_ac6a() {
        AppUser target = user(3L, "learner");
        target.softDelete(1L, Instant.now());
        when(appUserDao.findById(3L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.adminResetPassword(1L, 3L, "误操作"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户已删除")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.NOT_FOUND);
    }

    /** [AC-6a] 原因必填（审计硬条款）：空白原因 40000，不触达目标读取。 */
    @Test
    void adminResetPasswordRequiresReason_ac6a() {
        assertThatThrownBy(() -> service.adminResetPassword(1L, 3L, " "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.VALIDATION_FAILED);

        verify(appUserDao, never()).findById(anyLong());
    }

    /** [AC-6b] 删除成功：软删置位（操作者记录）、停用、会话全吊销；响应为删除后视图。 */
    @Test
    void adminDeleteUserSoftDeletesAndRevokesSessions_ac6b() {
        AppUser target = user(3L, "learner");
        when(appUserDao.findById(3L)).thenReturn(Optional.of(target));
        when(appUserDao.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var view = service.adminDeleteUser(1L, 3L, "用户申请注销");

        assertThat(view.deleted()).isTrue();
        assertThat(target.getDeletedAt()).isNotNull();
        assertThat(target.getDeletedBy()).isEqualTo(1L);
        assertThat(target.isEnabled()).isFalse();
        verify(refreshSessions).findByPrincipalName("learner");
    }

    /** [AC-6b] 已删账号再删：40400（软删惯例裁决，见实现注释），不再重复吊销。 */
    @Test
    void adminDeleteUserIsIdempotentBy404OnDeletedTarget_ac6b() {
        AppUser target = user(3L, "learner");
        target.softDelete(1L, Instant.now());
        when(appUserDao.findById(3L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.adminDeleteUser(1L, 3L, "重复提交"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户已删除")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.NOT_FOUND);

        verify(refreshSessions, times(0)).findByPrincipalName(any());
    }

    /** [AC-6b] 自己删自己被拒（40300）。 */
    @Test
    void adminDeleteUserRejectsSelf_ac6b() {
        when(appUserDao.findById(1L)).thenReturn(Optional.of(adminUser(1L, "admin")));

        assertThatThrownBy(() -> service.adminDeleteUser(1L, 1L, "手滑"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能删除自己的账号")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.ACCESS_DENIED);

        verify(appUserDao, never()).save(any());
    }

    /** [AC-6b] 最后一个管理员不可删（与停用同款行锁护栏）。 */
    @Test
    void adminDeleteUserRejectsLastAdmin_ac6b() {
        when(appUserDao.findById(3L)).thenReturn(Optional.of(adminUser(3L, "deputy")));
        when(appUserDao.countActiveAdminsForUpdate()).thenReturn(1L);

        assertThatThrownBy(() -> service.adminDeleteUser(1L, 3L, "团队调整"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能删除最后一个管理员")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT);

        verify(appUserDao, never()).save(any());
    }

    /** [AC-6b] 原因必填：空白原因 40000。 */
    @Test
    void adminDeleteUserRequiresReason_ac6b() {
        assertThatThrownBy(() -> service.adminDeleteUser(1L, 3L, ""))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.VALIDATION_FAILED);

        verify(appUserDao, never()).findById(anyLong());
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
