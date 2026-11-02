package io.github.codejinxz.yilian.system.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.util.PasswordUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.UsernameUtils;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.dao.InviteCodeDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.InviteCode;
import io.github.codejinxz.yilian.system.entity.UserRole;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.dto.RegisterCommand;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;
import io.github.codejinxz.yilian.system.service.dto.UserSettings;
import io.github.codejinxz.yilian.system.service.dto.UserSettingsPatch;
import io.github.codejinxz.yilian.system.service.vo.AdminUserView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeAdminView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeCreatedView;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserDao appUserDao;
    private final InviteCodeDao inviteCodeDao;
    private final PasswordEncoder passwordEncoder;
    private final boolean inviteRequired;

    /**
     * 注入账号 Dao、邀请码 Dao 与密码编码器。
     *
     * @param appUserDao 账号 Dao
     * @param inviteCodeDao 邀请码 Dao
     * @param passwordEncoder BCrypt 编码器
     * @param inviteRequired 是否强制邀请码注册
     */
    public UserAccountServiceImpl(
            AppUserDao appUserDao,
            InviteCodeDao inviteCodeDao,
            PasswordEncoder passwordEncoder,
            @Value("${yilian.registration.invite-required:false}") boolean inviteRequired) {
        this.appUserDao = appUserDao;
        this.inviteCodeDao = inviteCodeDao;
        this.passwordEncoder = passwordEncoder;
        this.inviteRequired = inviteRequired;
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
        String normalizedKeyword = StringUtils.isBlank(keyword) ? null : keyword.trim();
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
        } else {
            target.enable();
        }
        return AdminUserView.from(appUserDao.save(target));
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
        // 最后管理员行锁（013 AC-5）：降级前在事务内锁定全部现存管理员行（FOR UPDATE）再计数，
        // 把「读-判-写」串行化——两个并发降级必然一先一后，后到者在锁上读到提交后的最新计数，
        // count<=1 时拒绝，任何时刻都不可能出现 0 管理员状态。选型说明：count 聚合无法直接
        // FOR UPDATE，JPA 侧改为 @Lock(PESSIMISTIC_WRITE) 锁定管理员行集合后按行数计数；
        // ORDER BY id 保证锁定顺序唯一，并发事务不会交叉加锁而死锁。
        if (target.getRole() == UserRole.ADMIN
                && nextRole != UserRole.ADMIN
                && appUserDao.countActiveAdminsForUpdate() <= 1) {
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "不能降级最后一个管理员");
        }
        target.changeRole(nextRole);
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
