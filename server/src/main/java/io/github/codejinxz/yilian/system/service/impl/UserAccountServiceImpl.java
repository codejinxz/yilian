package io.github.codejinxz.yilian.system.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.session.RefreshSessionGuard;
import io.github.codejinxz.yilian.common.util.PasswordUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.UsernameUtils;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.dao.InviteCodeDao;
import io.github.codejinxz.yilian.system.dao.User2faRecoveryCodeDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.InviteCode;
import io.github.codejinxz.yilian.system.entity.UserRole;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.dto.RegisterCommand;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;
import io.github.codejinxz.yilian.system.service.dto.UserSettings;
import io.github.codejinxz.yilian.system.service.dto.UserSettingsPatch;
import io.github.codejinxz.yilian.system.service.vo.AdminPasswordResetView;
import io.github.codejinxz.yilian.system.service.vo.AdminUserView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeAdminView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeCreatedView;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 账号用例实现；邀请码只比对哈希，密码只落 BCrypt。 */
@Service
public class UserAccountServiceImpl implements UserAccountService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Set<String> RATING_STANDARDS = Set.of("TEM8", "CATTI", "IELTS");
    private static final Set<String> THEMES = Set.of("paper", "night");
    private static final Set<String> USER_STATUSES = Set.of("active", "disabled", "deleted", "all");

    private static final int MIN_PASSWORD_LENGTH = 8;

    /** 邀请码列表上限：管理端不翻页，只回最近 50 条，超出靠审计与数据库追溯。 */
    private static final int INVITE_LIST_LIMIT = 50;

    /** 邀请码随机码重摇次数：31 个字符取 8 位，空间足够大，仅防御极端哈希碰撞。 */
    private static final int INVITE_CODE_MAX_ATTEMPTS = 5;

    /** 随机码字母表：去掉易混的 I/L/O/0/1，方便口头转述；只出大写，与核销侧的归一一致。 */
    private static final String INVITE_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final int INVITE_CODE_RANDOM_LENGTH = 8;

    /** 管理员重置的临时口令长度：12 位 × 31 字母表 ≈ 61 bits，只显示一次、首次登录强制改密。 */
    private static final int TEMPORARY_PASSWORD_LENGTH = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserDao appUserDao;
    private final InviteCodeDao inviteCodeDao;
    private final User2faRecoveryCodeDao recoveryCodeDao;
    private final PasswordEncoder passwordEncoder;
    private final boolean inviteRequired;
    private final FindByIndexNameSessionRepository<? extends Session> refreshSessions;
    private final RefreshSessionGuard refreshSessionGuard;

    /**
     * 注入账号 Dao、邀请码 Dao、恢复码 Dao、密码编码器与 refresh 会话吊销依赖。
     *
     * @param appUserDao 账号 Dao
     * @param inviteCodeDao 邀请码 Dao
     * @param recoveryCodeDao 2FA 恢复码 Dao（管理员重置两步验证时清行）
     * @param passwordEncoder BCrypt 编码器
     * @param inviteRequired 是否强制邀请码注册
     * @param refreshSessions 按用户名索引的 refresh 会话仓库（停用账号时吊销会话）
     * @param refreshSessionGuard 吊销护栏：纪元先增再删会话，堵住并发刷新重建会话的窗口
     */
    public UserAccountServiceImpl(
            AppUserDao appUserDao,
            InviteCodeDao inviteCodeDao,
            User2faRecoveryCodeDao recoveryCodeDao,
            PasswordEncoder passwordEncoder,
            @Value("${yilian.registration.invite-required:false}") boolean inviteRequired,
            FindByIndexNameSessionRepository<? extends Session> refreshSessions,
            RefreshSessionGuard refreshSessionGuard) {
        this.appUserDao = appUserDao;
        this.inviteCodeDao = inviteCodeDao;
        this.recoveryCodeDao = recoveryCodeDao;
        this.passwordEncoder = passwordEncoder;
        this.inviteRequired = inviteRequired;
        this.refreshSessions = refreshSessions;
        this.refreshSessionGuard = refreshSessionGuard;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findByLogin(String login) {
        if (StringUtils.isBlank(login)) {
            return Optional.empty();
        }
        return appUserDao.findByLogin(login).map(UserCredentials::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findById(long id) {
        return appUserDao.findById(id).map(UserCredentials::from);
    }

    @Override
    @Transactional
    public UserCredentials register(RegisterCommand command) {
        String email = normalizeEmail(command.email());
        String username = resolveUsername(command.username(), email);
        validatePassword(command.password(), username, email);

        if (appUserDao.existsByEmail(email)) {
            throw new BusinessException(ApiCode.EMAIL_ALREADY_EXISTS);
        }
        if (appUserDao.existsByUsername(username)) {
            throw new BusinessException(ApiCode.USERNAME_ALREADY_EXISTS);
        }

        InviteCode invite = null;
        if (inviteRequired || StringUtils.isNotBlank(command.inviteCode())) {
            invite = requireInviteCode(command.inviteCode());
        }

        Instant now = Instant.now();
        AppUser user =
                AppUser.newUser(email, username, passwordEncoder.encode(command.password()), now);
        user = appUserDao.save(user);
        if (invite != null) {
            invite.markUsed(user.getId(), now);
            inviteCodeDao.save(invite);
        }
        return UserCredentials.from(user);
    }

    @Override
    @Transactional
    public void recordLogin(long userId) {
        appUserDao
                .findById(userId)
                .filter(user -> user.getDeletedAt() == null)
                .ifPresent(
                        user -> {
                            user.recordLogin(Instant.now());
                            appUserDao.save(user);
                        });
    }

    @Override
    @Transactional
    public void changePassword(long userId, String newPassword) {
        AppUser user = requireUser(userId);
        validatePassword(newPassword, user.getUsername(), user.getEmail());
        user.changePassword(passwordEncoder.encode(newPassword), Instant.now());
        appUserDao.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserSettings getSettings(long userId) {
        return UserSettings.from(requireUser(userId));
    }

    @Override
    @Transactional
    public UserSettings updateSettings(long userId, UserSettingsPatch patch) {
        if (patch == null || patch.isEmpty()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "至少提供一个设置项");
        }
        validateTimezone(patch.timezone());
        validateDailyGoal(patch.dailyGoal());
        validateRatingStandard(patch.ratingStandard());
        validateRetention(patch.desiredRetention());
        validateTheme(patch.theme());
        AppUser user = requireUser(userId);
        user.updateSettings(
                patch.timezone(),
                patch.dailyGoal(),
                patch.ratingStandard(),
                patch.desiredRetention(),
                patch.theme(),
                patch.notificationsEnabled());
        return UserSettings.from(appUserDao.save(user));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminUserView> adminSearch(
            String keyword, String role, String status, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ApiCode.INVALID_PAGINATION);
        }
        String normalizedStatus = StringUtils.defaultIfBlank(status, "all");
        if (!USER_STATUSES.contains(normalizedStatus)) {
            throw new BusinessException(ApiCode.INVALID_PAGINATION, "status 不受支持");
        }
        // keyword 走 LIKE 转义（StringUtils.escapeLike）：用户输入的 %/_ 是字面量，
        // 检索语义=纯 contains。
        String normalizedKeyword = StringUtils.escapeLike(keyword);
        Page<AppUser> result =
                appUserDao.search(
                        normalizedKeyword,
                        parseRoleFilter(role),
                        normalizedStatus,
                        PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.of(
                result.getContent().stream().map(AdminUserView::from).toList(),
                page,
                size,
                result.getTotalElements());
    }

    @Override
    @Transactional
    public AdminUserView adminSetStatus(
            long actorId, long targetId, boolean enabled, String reason) {
        AppUser target = requireUser(targetId);
        if (target.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "用户已删除");
        }
        if (!enabled) {
            if (targetId == actorId) {
                throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "不能停用自己");
            }
            if (StringUtils.isBlank(reason)) {
                throw new BusinessException(ApiCode.VALIDATION_FAILED, "停用必须填写原因");
            }
            if (target.getRole() == UserRole.ADMIN
                    && appUserDao.countActiveAdminsForUpdate() <= 1) {
                throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "不能停用最后一个管理员");
            }
            target.disable(reason.trim(), Instant.now());
            // 停用即吊销该用户全部 refresh 会话（契约「登出/改密/停用吊销全部」）：
            // 会话行若保留 30 天，重新启用后泄露的旧 cookie 无需密码即可复活。吊销护栏纪元先增，
            // 并发 in-flight 刷新即便滞留重建会话，下一次刷新也被纪元拒绝。吊销动作发起于事务内
            // 而 Spring Session 仓库自治事务：本事务若回滚，代价是多吊销（重新登录即可），方向安全。
            revokeRefreshSessions(target.getUsername());
        } else {
            target.enable();
        }
        return AdminUserView.from(appUserDao.save(target));
    }

    @Override
    @Transactional
    public AdminPasswordResetView adminResetPassword(long actorId, long targetId, String reason) {
        if (StringUtils.isBlank(reason)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "重置密码必须填写原因");
        }
        AppUser target = requireUser(targetId);
        if (target.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "用户已删除");
        }
        if (targetId == actorId) {
            // 自己重置自己被拒：管理员有自助改密端点，走 /me/password；
            // 40300 与「自己删自己」同口径——这是权限边界而非状态冲突。
            throw new BusinessException(ApiCode.ACCESS_DENIED, "不能重置自己的密码，请走自助改密");
        }
        String temporaryPassword = generateTemporaryPassword();
        target.adminResetPassword(passwordEncoder.encode(temporaryPassword), Instant.now());
        appUserDao.save(target);
        // 改密即吊销全部 refresh 会话（契约「改密吊销全部」）：旧会话拿到的 access token 过期后
        // 无法续期，新登录必须用临时口令并被 40303 引导改密。
        revokeRefreshSessions(target.getUsername());
        return new AdminPasswordResetView(
                temporaryPassword, Long.toString(target.getId()), target.getUsername());
    }

    @Override
    @Transactional
    public void adminReset2fa(long actorId, long targetId, String reason) {
        if (StringUtils.isBlank(reason)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "重置两步验证必须填写原因");
        }
        AppUser target = requireUser(targetId);
        if (target.getDeletedAt() != null) {
            // 与 adminResetPassword/adminDeleteUser 同口径：目标已删按 40400 显式报出。
            throw new BusinessException(ApiCode.NOT_FOUND, "用户已删除");
        }
        if (!target.isTotpEnabled() && target.getTotpSecretCipher() == null) {
            // 未注册 2FA 是无状态变化的无害操作：幂等返回，不吊销会话（无凭据变更可吊销），
            // 审计行照常落库（动作有理由即可见）。
            return;
        }
        // 清三列 + 删恢复码行 + 吊销全部 refresh 会话，三者同事务口径与「改密吊销全部」一致：
        // 重置后该用户任何在途会话都不能再免验证续期，重新登录走完整密码 + 重新注册 2FA。
        target.clearTotp();
        appUserDao.save(target);
        recoveryCodeDao.deleteByUserId(targetId);
        revokeRefreshSessions(target.getUsername());
    }

    @Override
    @Transactional
    public AdminUserView adminDeleteUser(long actorId, long targetId, String reason) {
        if (StringUtils.isBlank(reason)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "删除账号必须填写原因");
        }
        AppUser target = requireUser(targetId);
        if (target.getDeletedAt() != null) {
            // 已删按 40400 收敛：与 adminSetStatus/adminSetRole 的软删口径一致；
            // 幂等 200 会把「目标不存在」伪装成成功，审计行也会失真，故显式报「用户已删除」。
            throw new BusinessException(ApiCode.NOT_FOUND, "用户已删除");
        }
        if (targetId == actorId) {
            throw new BusinessException(ApiCode.ACCESS_DENIED, "不能删除自己的账号");
        }
        // 与「停用最后一个管理员」同款护栏（行锁）：删除即失去登录能力，
        // 两个管理员并发互删时行锁串行化，任何时刻不出现 0 活跃管理员。
        if (target.getRole() == UserRole.ADMIN && appUserDao.countActiveAdminsForUpdate() <= 1) {
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "不能删除最后一个管理员");
        }
        // 软删 + 清 2FA + 吊销全部会话同事务（密钥材料不留给已删账号，与 selfDelete 同口径：
        // 管理员删除与自助删号对 2FA 材料的处置必须一致，TOTP 密文与恢复码哈希都不等到匿名化才清）。
        target.softDelete(actorId, Instant.now());
        target.clearTotp();
        appUserDao.save(target);
        recoveryCodeDao.deleteByUserId(targetId);
        // 软删即吊销全部 refresh 会话：deleted=true 的账号 refresh 校验拒绝，无需等 access token 过期。
        revokeRefreshSessions(target.getUsername());
        return AdminUserView.from(target);
    }

    /**
     * 生成一次性临时口令：与邀请码同风格——去混淆字母表的 SecureRandom，12 位约 61 bits， 满足最低 8 位长度要求且不会命中「与用户名/邮箱相同」的弱口令校验。
     *
     * @return 12 位随机口令
     */
    private static String generateTemporaryPassword() {
        StringBuilder random = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
        for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
            random.append(
                    INVITE_CODE_ALPHABET.charAt(
                            SECURE_RANDOM.nextInt(INVITE_CODE_ALPHABET.length())));
        }
        return random.toString();
    }

    @Override
    @Transactional
    public Instant selfDelete(long userId, Instant now) {
        AppUser target = requireUser(userId);
        if (target.getDeletedAt() != null) {
            // 已删重复调用按 40900 收敛（契约文档化口径）：幂等 200 会把「账号已删除」伪装成
            // 二次成功，回执时间也会误导；显式冲突与 adminDeleteUser 的 40400 语义分层——
            // 40900 表达「同一个删除已经发生」，调用方（持过期 access token 的本人）据此停手。
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "账号已删除");
        }
        if (target.getRole() == UserRole.ADMIN && appUserDao.countActiveAdminsForUpdate() <= 1) {
            // 与 adminDeleteUser 同款「最后一个管理员」行锁护栏：管理员自助删号等同自我降级，
            // 任何时刻不出现 0 活跃管理员；最后一个管理员的删号须先由他人接管或另建管理员。
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "不能删除最后一个管理员");
        }
        // 软删 + 清 2FA + 吊销全部会话同事务（密钥材料不留给已删账号；会话吊销
        // 与 adminDeleteUser 同口径——deleted 账号 refresh 校验本就拒绝，吊销让窗口立即收敛）。
        // 学习记录 append-only 一律不动；匿名化由日调度任务在 deleted_at+30 天后执行。
        target.softDelete(userId, now);
        target.clearTotp();
        appUserDao.save(target);
        recoveryCodeDao.deleteByUserId(userId);
        revokeRefreshSessions(target.getUsername());
        return target.getDeletedAt();
    }

    @Override
    @Transactional
    public boolean anonymizeUser(long userId, Instant now) {
        AppUser target = requireUser(userId);
        if (target.getDeletedAt() == null || target.getAnonymizedAt() != null) {
            // 幂等重入：未删（未到期或误扫）与已匿名化（重复调度）都零变更。
            return false;
        }
        // 先按软删时的用户名吊销会话、后改用户名：refresh 会话按 principal_name（登录时的
        // 用户名）索引，先改后吊会按新占位名扑空，留下「匿名化后仍有活会话」的窗口。
        revokeRefreshSessions(target.getUsername());
        target.anonymize(userId, now);
        appUserDao.save(target);
        recoveryCodeDao.deleteByUserId(userId);
        return true;
    }

    /**
     * 按用户名吊销全部 refresh 会话（纪元先增 + 会话行删除，见 {@link RefreshSessionGuard#revoke}）。
     *
     * @param username 目标用户的登录名
     */
    private void revokeRefreshSessions(String username) {
        refreshSessionGuard.revoke(
                username,
                () ->
                        refreshSessions
                                .findByPrincipalName(username)
                                .keySet()
                                .forEach(refreshSessions::deleteById));
    }

    @Override
    @Transactional
    public AdminUserView adminSetRole(long actorId, long targetId, String role, String reason) {
        if (StringUtils.isBlank(reason)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "变更角色必须填写原因");
        }
        UserRole nextRole = parseRoleValue(role);
        AppUser target = requireUser(targetId);
        if (target.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "用户已删除");
        }
        if (targetId == actorId) {
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "不能变更自己的角色");
        }
        // 最后管理员行锁：降级前在事务内锁定全部现存管理员行（FOR UPDATE）再计数，
        // 把「读-判-写」串行化——两个并发降级必然一先一后，后到者在锁上读到提交后的最新计数，
        // count<=1 时拒绝，任何时刻都不可能出现 0 管理员状态。选型说明：count 聚合无法直接
        // FOR UPDATE，JPA 侧改为 @Lock(PESSIMISTIC_WRITE) 锁定管理员行集合后按行数计数；
        // ORDER BY id 保证锁定顺序唯一，并发事务不会交叉加锁而死锁。
        if (target.getRole() == UserRole.ADMIN
                && nextRole != UserRole.ADMIN
                && appUserDao.countActiveAdminsForUpdate() <= 1) {
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "不能降级最后一个管理员");
        }
        UserRole previousRole = target.getRole();
        target.changeRole(nextRole);
        // 降级即吊销该用户全部 refresh 会话（与 adminSetStatus 同款）：ADMIN 的
        // access token 最长还有 10–15 分钟窗口，refresh 会话若保留，泄露的 cookie 在降级后仍可
        // 静默续期；吊销强制重新登录，权限收敛即时生效。仅 ADMIN→非 ADMIN 触发——平调与升级
        // 不收缩权限，不扰动会话；同角色重复提交无状态变化，同样不吊销。
        if (previousRole == UserRole.ADMIN && nextRole != UserRole.ADMIN) {
            revokeRefreshSessions(target.getUsername());
        }
        return AdminUserView.from(appUserDao.save(target));
    }

    @Override
    @Transactional
    public InviteCodeCreatedView adminCreateInvite(long actorId) {
        Instant now = Instant.now();
        for (int attempt = 0; attempt < INVITE_CODE_MAX_ATTEMPTS; attempt++) {
            String code = generateInviteCode();
            String hash = sha256(code);
            if (inviteCodeDao.findByCodeHash(hash).isPresent()) {
                // 唯一键 uk_invite_code_hash 兜底：撞哈希就重摇，不把约束异常漏给调用方。
                continue;
            }
            // 前缀取 "YL-XXXX"（前 7 位），与库列 VARCHAR(12) 留有余量；不设过期，回收走软删。
            InviteCode saved =
                    inviteCodeDao.save(
                            InviteCode.newSingleUse(
                                    hash, code.substring(0, 7), null, actorId, now));
            return new InviteCodeCreatedView(code, saved.getCodePrefix(), saved.getCreatedAt());
        }
        throw new BusinessException(ApiCode.INTERNAL_ERROR, "邀请码生成冲突，请重试");
    }

    @Override
    @Transactional(readOnly = true)
    public List<InviteCodeAdminView> adminListInvites() {
        List<InviteCode> codes = inviteCodeDao.findRecent(INVITE_LIST_LIMIT);
        List<Long> usedByIds =
                codes.stream().map(InviteCode::getUsedBy).filter(Objects::nonNull).toList();
        Map<Long, String> usernamesById =
                usedByIds.isEmpty()
                        ? Map.of()
                        : appUserDao.findByIds(usedByIds).stream()
                                .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername));
        return codes.stream()
                .map(
                        code ->
                                new InviteCodeAdminView(
                                        code.getCodePrefix(),
                                        code.getUsedCount() >= 1,
                                        code.getUsedBy() == null
                                                ? null
                                                : usernamesById.get(code.getUsedBy()),
                                        code.getCreatedAt()))
                .toList();
    }

    /**
     * 生成一次性明文邀请码：{@code YL-XXXX-XXXX}，随机部分取自去混字母表。
     *
     * @return 新明文邀请码
     */
    private static String generateInviteCode() {
        StringBuilder random = new StringBuilder(INVITE_CODE_RANDOM_LENGTH);
        for (int i = 0; i < INVITE_CODE_RANDOM_LENGTH; i++) {
            random.append(
                    INVITE_CODE_ALPHABET.charAt(
                            SECURE_RANDOM.nextInt(INVITE_CODE_ALPHABET.length())));
        }
        return "YL-" + random.substring(0, 4) + "-" + random.substring(4);
    }

    /**
     * 解析列表筛选的角色参数：空白表示不过滤，只接受 USER/ADMIN。
     *
     * @param role 前端传入的角色字符串，可为 null
     * @return 角色枚举；不过滤时为 null
     */
    private static UserRole parseRoleFilter(String role) {
        if (StringUtils.isBlank(role)) {
            return null;
        }
        return parseRoleValue(role);
    }

    /**
     * 解析必填角色字符串为枚举，非法值按参数校验失败收敛，避免 IllegalArgumentException 变 500。
     *
     * @param role USER/ADMIN
     * @return 角色枚举
     */
    private static UserRole parseRoleValue(String role) {
        if (StringUtils.isBlank(role)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "role 必填");
        }
        try {
            return UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "role 不受支持");
        }
    }

    private AppUser requireUser(long userId) {
        return appUserDao
                .findById(userId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "用户不存在"));
    }

    private String normalizeEmail(String email) {
        if (StringUtils.isBlank(email) || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "邮箱格式不正确");
        }
        String normalized = email.trim();
        if (normalized.length() > 255) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "邮箱最多 255 个字符");
        }
        return normalized;
    }

    private String resolveUsername(String requested, String email) {
        String username = requested;
        if (StringUtils.isBlank(username)) {
            String localPart = email.substring(0, email.indexOf('@'));
            username = localPart.replaceAll("[^A-Za-z0-9._-]", "-");
            if (username.length() < 3) {
                username = "u-" + username;
            }
            if (username.length() > 64) {
                username = username.substring(0, 64);
            }
        }
        if (!UsernameUtils.isValid(username)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "用户名需为 3–64 位字母、数字、点、下划线或连字符");
        }
        if (appUserDao.existsByUsername(username)) {
            if (StringUtils.isBlank(requested)) {
                username = uniqueDerivedUsername(username);
            } else {
                throw new BusinessException(ApiCode.USERNAME_ALREADY_EXISTS);
            }
        }
        return username;
    }

    private String uniqueDerivedUsername(String base) {
        for (int suffix = 1; suffix <= 20; suffix++) {
            String candidate =
                    base.length() <= 60
                            ? base + "-" + suffix
                            : base.substring(0, 60) + "-" + suffix;
            if (!appUserDao.existsByUsername(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ApiCode.USERNAME_ALREADY_EXISTS);
    }

    private void validatePassword(String password, String username, String email) {
        if (StringUtils.isBlank(password) || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ApiCode.WEAK_PASSWORD, "密码至少 8 个字符");
        }
        if (PasswordUtils.exceedsBcryptLimit(password)) {
            throw new BusinessException(ApiCode.WEAK_PASSWORD, "密码最多 72 字节");
        }
        if (password.equalsIgnoreCase(username) || password.equalsIgnoreCase(email)) {
            throw new BusinessException(ApiCode.WEAK_PASSWORD, "密码不能与用户名或邮箱相同");
        }
    }

    private InviteCode requireInviteCode(String rawCode) {
        if (StringUtils.isBlank(rawCode)) {
            throw new BusinessException(ApiCode.INVALID_INVITE_CODE);
        }
        String normalized = rawCode.trim().toUpperCase(Locale.ROOT);
        InviteCode code =
                inviteCodeDao
                        .findByCodeHash(sha256(normalized))
                        .orElseThrow(() -> new BusinessException(ApiCode.INVALID_INVITE_CODE));
        if (!code.usableAt(Instant.now())) {
            if (code.getUsedCount() >= 1 || code.getUsedBy() != null) {
                throw new BusinessException(ApiCode.INVITE_ALREADY_USED);
            }
            throw new BusinessException(ApiCode.INVALID_INVITE_CODE);
        }
        return code;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    private static void validateTimezone(String timezone) {
        if (timezone == null) {
            return;
        }
        try {
            ZoneId.of(timezone);
        } catch (java.time.DateTimeException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "timezone 必须是 IANA 时区");
        }
    }

    private static void validateDailyGoal(Integer dailyGoal) {
        if (dailyGoal != null && dailyGoal != 1 && dailyGoal != 3 && dailyGoal != 5) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "dailyGoal 只能是 1、3 或 5");
        }
    }

    private static void validateRatingStandard(String ratingStandard) {
        if (ratingStandard != null && !RATING_STANDARDS.contains(ratingStandard)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "ratingStandard 不受支持");
        }
    }

    private static void validateRetention(BigDecimal retention) {
        if (retention != null
                && (retention.compareTo(new BigDecimal("0.800")) < 0
                        || retention.compareTo(new BigDecimal("0.970")) > 0)) {
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED, "desiredRetention 需在 0.800–0.970");
        }
    }

    private static void validateTheme(String theme) {
        if (theme != null && !THEMES.contains(theme)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "theme 只能是 paper 或 night");
        }
    }
}
