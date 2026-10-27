package io.github.codejinxz.yilian.system.service.impl;

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
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 注入配置、账号 Dao 与 refresh 会话仓库；后者用于改密后吊销旧会话。
     *
     * @param properties bootstrap 管理员配置
     * @param appUserDao 账号表 Dao
     * @param passwordEncoder 密码编码器
     * @param environment 用于判断是否 prod profile
     * @param sessionRepository Spring Session 仓库，按用户名吊销 refresh 会话
     */
    public BootstrapAdminServiceImpl(
            BootstrapAdminProperties properties,
            AppUserDao appUserDao,
            PasswordEncoder passwordEncoder,
            Environment environment,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.properties = properties;
        this.appUserDao = appUserDao;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.sessionRepository = sessionRepository;
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

        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        String username = properties.username();
        String password = properties.password();

        validate(username, password, prod);
        Optional<AppUser> existing = appUserDao.findByUsername(username);

        if (existing.isEmpty()) {
            AppUser admin =
                    AppUser.newAdmin(username, passwordEncoder.encode(password), Instant.now());
            appUserDao.save(admin);
            log.info("bootstrap admin created username={} prod={}", username, prod);
        } else {
            AppUser admin = existing.get();
            boolean changed = false;
            if (properties.resetPasswordOnStartup()) {
                admin.resetPassword(passwordEncoder.encode(password), Instant.now());
                revokeRefreshSessions(username);
                changed = true;
                log.warn("bootstrap admin password reset username={}", username);
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
