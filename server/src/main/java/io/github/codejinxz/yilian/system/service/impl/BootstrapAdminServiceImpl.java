package io.github.codejinxz.yilian.system.service.impl;

import io.github.codejinxz.yilian.common.config.DeploymentMode;
import io.github.codejinxz.yilian.common.session.RefreshSessionGuard;
import io.github.codejinxz.yilian.common.util.PasswordUtils;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.common.util.UsernameUtils;
import io.github.codejinxz.yilian.system.bootstrap.BootstrapAdminProperties;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.UserRole;
import io.github.codejinxz.yilian.system.service.BootstrapAdminService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;

/**
 * bootstrap 管理员初始化实现，按「校验 → 查找 → 创建或修复」顺序幂等执行。
 *
 * <p>local 允许文档化的默认密码；prod 拒绝缺失或默认密码并直接启动失败。密码落库前一律 BCrypt 哈希，且从不写日志。
 */
@Service
@Transactional
public class BootstrapAdminServiceImpl implements BootstrapAdminService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminServiceImpl.class);

    private final BootstrapAdminProperties properties;
    private final AppUserDao appUserDao;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final RefreshSessionGuard refreshSessionGuard;

    /**
     * 注入配置、账号 Dao 与 refresh 会话仓库；后者用于改密后吊销旧会话。
     *
     * @param properties bootstrap 管理员配置
     * @param appUserDao 账号表 Dao
     * @param passwordEncoder 密码编码器
     * @param environment 用于判断是否 prod profile
     * @param sessionRepository Spring Session 仓库，按用户名吊销 refresh 会话
     * @param refreshSessionGuard 吊销护栏：纪元先增再删会话，堵住并发刷新重建会话的窗口
     */
    public BootstrapAdminServiceImpl(
            BootstrapAdminProperties properties,
            AppUserDao appUserDao,
            PasswordEncoder passwordEncoder,
            Environment environment,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository,
            RefreshSessionGuard refreshSessionGuard) {
        this.properties = properties;
        this.appUserDao = appUserDao;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.sessionRepository = sessionRepository;
        this.refreshSessionGuard = refreshSessionGuard;
    }

    /**
     * 幂等初始化 bootstrap 管理员。
     *
     * <p>账号不存在则创建；已存在时仅在「开启一次性改密」、被停用或角色不是管理员时修复， 健康账号原样保留，避免每次重启都重置密码或覆盖人工调整。
     *
     * @throws IllegalStateException 配置不合法（用户名格式、prod 缺失/弱密码）时抛出，阻止应用启动
     */
    @Override
    public void initialize() {
        if (!properties.enabled()) {
            log.info("bootstrap admin disabled");
            return;
        }

        boolean prod = DeploymentMode.isProd(environment);
        String username = properties.username();
        String password = properties.password();

        validate(username, password, prod);
        Optional<AppUser> existing = appUserDao.findByUsername(username);

        if (existing.isEmpty()) {
            AppUser admin =
                    AppUser.newAdmin(username, passwordEncoder.encode(password), Instant.now());
            appUserDao.save(admin);
            if (properties.resetPasswordOnStartup()) {
                // 创建即「该配置口令已生效」：标记一并落盘，保证第二次重启不会被误判为未应用。
                writeResetMarker(password);
            }
            log.info("bootstrap admin created username={} prod={}", username, prod);
        } else {
            AppUser admin = existing.get();
            boolean changed = false;
            if (properties.resetPasswordOnStartup()) {
                // 一次性开关的「用后持久化失效」（018 AC-3，review-verify-2 03#05）：标记文件记录
                // 上次生效配置口令的 BCrypt 哈希，重启时配置值未变即跳过——管理员改密后忘关开关，
                // 也不会把口令静默重置回配置值、更不会重复吊销会话。016 的「同值幂等」只兜住
                // 「配置值 == 当前口令」，管理员改密后（当前口令 ≠ 配置值）仍会被覆盖，这里补上。
                // 如需再次重置，把配置口令换成新值（新哈希 ≠ 标记值即执行一次并更新标记）。
                if (isResetAlreadyApplied(password)) {
                    log.warn(
                            "bootstrap admin reset-password-on-startup skipped:"
                                    + " configured password already applied username={}",
                            username);
                } else {
                    admin.resetPassword(passwordEncoder.encode(password), Instant.now());
                    refreshSessionGuard.revoke(username, () -> revokeRefreshSessions(username));
                    writeResetMarker(password);
                    changed = true;
                    log.warn("bootstrap admin password reset username={}", username);
                }
            }
            if (!admin.isEnabled()) {
                admin.enable();
                changed = true;
            }
            if (admin.getRole() != UserRole.ADMIN) {
                admin.promoteToAdmin();
                changed = true;
            }
            if (changed) {
                appUserDao.save(admin);
                log.warn("bootstrap admin repaired username={}", username);
            } else {
                log.info("bootstrap admin already present username={}", username);
            }
        }

        if (!prod && BootstrapAdminProperties.DEFAULT_PASSWORD.equals(password)) {
            log.warn(
                    "bootstrap admin is using the local default password; change it before sharing this database");
        }
    }

    /**
     * 判断配置口令是否已经通过一次性开关生效过：标记文件存在且其记录的 BCrypt 哈希与配置值匹配。
     *
     * <p>选型裁决（AC-3）：标记存 BCrypt 哈希而非 SHA-256——配置恢复口令可能是弱值， BCrypt 使标记文件泄漏后也无法低成本反推；
     * 存哈希而非明文，与「密钥不落盘」的口径一致。 文件读取失败按「未应用」处理会退回 016 行为（可能覆盖口令），因此读取异常时 保守地视为已应用（跳过重置），
     * 宁可不恢复也不能覆盖管理员已改的口令——两害取其轻。
     *
     * @param configuredPassword 配置的一次性恢复口令
     * @return true 表示该配置值已生效过，本次重启跳过重置
     */
    private boolean isResetAlreadyApplied(String configuredPassword) {
        Path marker = Path.of(properties.resetMarkerFile());
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            String storedHash = Files.readString(marker).trim();
            return passwordEncoder.matches(configuredPassword, storedHash);
        } catch (IOException | RuntimeException exception) {
            log.error(
                    "failed to read bootstrap reset marker {}; skipping reset to avoid overwriting admin password",
                    marker,
                    exception);
            return true;
        }
    }

    /**
     * 把本次生效的配置口令哈希写入标记文件（临时文件 + 原子替换，避免半写状态）。
     *
     * <p>本类是 {@code @Transactional}：标记若在提交前落盘，事务回滚（如后续修复语句失败）会留下 「库里没生效、标记却说已生效」的反向漂移，
     * 下次启动会跳过本应执行的重置。因此活动事务中注册 afterCommit 回调， 无事务（单测直调）时立即写。
     *
     * <p>写入失败只记 ERROR 不阻断启动：标记丢失的后果是退回 016 的「同值幂等」行为， 比阻断管理员恢复流程更可接受；数据目录与数据库同机， 磁盘不可写时数据库同样无法工作。
     *
     * @param configuredPassword 本次已生效的配置口令
     */
    private void writeResetMarker(String configuredPassword) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doWriteResetMarker(configuredPassword);
                        }
                    });
            return;
        }
        doWriteResetMarker(configuredPassword);
    }

    /**
     * 实际写标记文件；只在事务提交后或无事务时调用。
     *
     * @param configuredPassword 本次已生效的配置口令
     */
    private void doWriteResetMarker(String configuredPassword) {
        Path marker = Path.of(properties.resetMarkerFile());
        try {
            if (marker.getParent() != null) {
                Files.createDirectories(marker.getParent());
            }
            Path staged = marker.resolveSibling(marker.getFileName() + ".tmp");
            Files.writeString(staged, passwordEncoder.encode(configuredPassword));
            Files.move(
                    staged,
                    marker,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            log.error("failed to write bootstrap reset marker {}", marker, exception);
        }
    }

    /**
     * 按 principal 索引吊销该用户的全部 refresh 会话，使改密立即使旧会话失效。
     *
     * @param username 登录名，即 Spring Session 的 principal 名
     */
    private void revokeRefreshSessions(String username) {
        sessionRepository
                .findByPrincipalName(username)
                .keySet()
                .forEach(sessionRepository::deleteById);
        log.warn("bootstrap admin refresh sessions revoked username={}", username);
    }

    /**
     * 启动前校验凭据，使错误配置尽早暴露；prod 额外要求非默认、足够长、与用户名不同且不在弱口令表。
     *
     * @param username 登录名
     * @param password 明文密码，只用于校验与随后编码
     * @param prod 是否 prod profile
     * @throws IllegalStateException 任一规则不满足时抛出
     */
    private static void validate(String username, String password, boolean prod) {
        if (!UsernameUtils.isValid(username)) {
            throw new IllegalStateException(
                    "yilian.bootstrap-admin.username must be 3-64 characters of letters, digits, '.', '_' or '-'");
        }
        if (prod
                && (StringUtils.isBlank(password)
                        || BootstrapAdminProperties.DEFAULT_PASSWORD.equals(password))) {
            throw new IllegalStateException(
                    "Refusing to start in prod: set YILIAN_BOOTSTRAP_ADMIN_PASSWORD to a non-default value");
        }
        if (prod) {
            PasswordUtils.prodWeakness(username, password)
                    .ifPresent(
                            reason -> {
                                throw new IllegalStateException("prod bootstrap admin " + reason);
                            });
        }
        if (StringUtils.isBlank(password)) {
            throw new IllegalStateException(
                    "Missing bootstrap admin password: set YILIAN_BOOTSTRAP_ADMIN_PASSWORD");
        }
        if (PasswordUtils.exceedsBcryptLimit(password)) {
            throw new IllegalStateException("Bootstrap admin password must be at most 72 bytes");
        }
    }
}
