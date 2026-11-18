package io.github.codejinxz.yilian.system.job;

import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.service.UserAccountService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 账号匿名化日调度：把「冷静期到期」的软删账号做不可逆匿名化。
 *
 * <p>删号当下只软删（deleted_at 落值、吊销会话、清 2FA），30 天冷静期内数据原样保留（管理员侧可查、 争议期）；到期由本任务逐用户执行 {@link
 * UserAccountService#anonymizeUser}——身份字段替换/清空、 个人化设置回默认、TOTP 材料清除、按软删时的用户名吊销会话，anonymized_at 落值。
 * 学习记录（attempts/attempt_gradings/appeals/review_logs）append-only 一律不动。
 *
 * <p>可重入性：扫描条件 {@code deleted_at <= now-30d AND anonymized_at IS NULL} 天然幂等—— 已匿名化的账号不再命中；单用户处置在
 * {@code anonymizeUser} 的独立事务内完成， 单个失败只记日志不阻塞批次，下次调度重试。冷静期天数走配置键与删号回执 （{@code AuthService}）同源，避免「承诺
 * 30 天、实际 29 天」的口径漂移。
 *
 * <p>审计口径：匿名化是自动化动作，操作者为调度任务而非管理员，不落 admin_audit_log（该表 以管理员操作者为语义前提，actor
 * 字段无法如实承载）；自动化可见性由本类的结构化日志承接 （逐用户一行，含 userId/deletedAt/anonymizedAt），删号行为本身已有 SELF_DELETE 审计行可追溯。
 */
@Component
public class AccountAnonymizationJob {

    private static final Logger log = LoggerFactory.getLogger(AccountAnonymizationJob.class);

    /** 单轮匿名化账号上限：常态个位数，异常积压时分轮消化，不拖死调度线程。 */
    static final int BATCH_LIMIT = 200;

    private final AppUserDao appUserDao;
    private final UserAccountService userAccountService;
    private final Clock clock;
    private final int graceDays;

    /**
     * 注入账号 Dao、账号用例、时钟与冷静期配置。
     *
     * @param appUserDao 账号 Dao
     * @param userAccountService 账号用例（单用户匿名化入口）
     * @param clock 全局时钟（测试注入固定时钟）
     * @param graceDays 冷静期天数，默认 30；与删号回执的匿名化时点同一配置键
     */
    public AccountAnonymizationJob(
            AppUserDao appUserDao,
            UserAccountService userAccountService,
            Clock clock,
            @Value("${yilian.account.anonymize-grace-days:30}") int graceDays) {
        this.appUserDao = appUserDao;
        this.userAccountService = userAccountService;
        this.clock = clock;
        this.graceDays = graceDays;
    }

    /**
     * 冷静期天数。
     *
     * @return 配置的天数
     */
    public int graceDays() {
        return graceDays;
    }

    /**
     * 匿名化截止时刻：当前时钟减去冷静期。
     *
     * @return 截止时刻（UTC），deleted_at 早于等于该值的软删账号到期
     */
    public Instant anonymizationCutoff() {
        return clock.instant().minus(Duration.ofDays(graceDays));
    }

    /**
     * 每日调度入口：扫描到期账号并逐用户匿名化；单用户失败只记日志，下次调度重试。
     *
     * @return 本次实际匿名化的账号数
     */
    @Scheduled(cron = "${yilian.account.anonymization-cron:0 40 3 * * *}")
    public int anonymizeDueUsers() {
        List<AppUser> due = appUserDao.findAnonymizationDue(anonymizationCutoff());
        int processed = 0;
        for (AppUser user : due) {
            if (processed >= BATCH_LIMIT) {
                log.info(
                        "account anonymization batch limit {} reached, rest next run", BATCH_LIMIT);
                break;
            }
            try {
                Instant now = clock.instant();
                if (userAccountService.anonymizeUser(user.getId(), now)) {
                    processed++;
                    // 自动化审计行（AGENTS §2.9「所有自动化可见、有理由」）：身份字段已处置的
                    // 留痕；不含密码哈希/TOTP 材料，email 匿名化后为 NULL 无泄露面。
                    log.info(
                            "account anonymized: userId={}, deletedAt={}, anonymizedAt={},"
                                    + " usernamePlaceholder=deleted_user_{}",
                            user.getId(),
                            user.getDeletedAt(),
                            now,
                            user.getId());
                }
            } catch (RuntimeException exception) {
                log.error("failed to anonymize account {}", user.getId(), exception);
            }
        }
        if (processed > 0) {
            log.info(
                    "account anonymization processed {} accounts (graceDays={})",
                    processed,
                    graceDays);
        }
        return processed;
    }
}
