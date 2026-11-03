package io.github.codejinxz.yilian.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.support.TestDatabases;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.vo.AdminUserView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 「最后一个管理员」行锁的并发回归测试（013 AC-5）：真实 MySQL + 真实事务。
 *
 * <p>复刻真实竞态：两个管理员并发互降（A 降 B、B 降 A）。没有行锁时两个事务都读到 旧计数 2、都放行，出现 0 管理员状态；行锁实现下必然一先一后——后到者在 FOR UPDATE 上
 * 等待先者提交，读到最新计数 1 而被拒绝。测试库为 yilian_test（前置条件与其他集成测试一致： YILIAN_TEST_DB_* 环境变量，见 docs/BOOTSTRAP.md
 * §5）。
 */
@SpringBootTest(
        properties = {"yilian.ratelimit.enabled=false", "management.health.redis.enabled=false"})
@ActiveProfiles("test")
class UserAdminLockConcurrencyIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";

    private static final String RACE_USERNAME = "lock-race-admin";

    @Autowired private UserAccountService userAccountService;

    @Autowired private AppUserDao appUserDao;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    /**
     * 播种竞态现场：bootstrap 管理员 + 一个测试管理员，恰好两名启用管理员。
     *
     * <p>其他集成测试可能在 yilian_test 留下别的管理员（如 it-importer），统一降为普通用户， 保证「恰好两名」的前置条件与测试执行顺序无关。
     */
    @BeforeEach
    void seedRaceScene() {
        cleanup();
        jdbcTemplate.update(
                "UPDATE users SET role = 'USER' WHERE username NOT IN (?, ?)",
                ADMIN_USERNAME,
                RACE_USERNAME);
        jdbcTemplate.update(
                "UPDATE users SET role = 'ADMIN', enabled = b'1', deleted_at = NULL"
                        + " WHERE username = ?",
                ADMIN_USERNAME);
        AppUser raceAdmin =
                AppUser.newAdmin(
                        RACE_USERNAME, "race-admin-hash", Instant.parse("2026-11-01T00:00:00Z"));
        appUserDao.save(raceAdmin);

        assertThat(activeAdminCount()).as("竞态前置：恰好两名启用管理员").isEqualTo(2);
    }

    /** 清理竞态现场：删除测试管理员、把 bootstrap 管理员复原为 ADMIN，保证重复运行与其他用例幂等。 */
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", RACE_USERNAME);
        jdbcTemplate.update(
                "UPDATE users SET role = 'ADMIN', enabled = b'1' WHERE username = ?",
                ADMIN_USERNAME);
    }

    /** [AC-5] 两名管理员并发互降：仅一人成功，另一人 40900 友好文案，库内恒有 1 名管理员。 */
    @Test
    void concurrentMutualDemotionAllowsExactlyOneSuccess_ac5() throws Exception {
        AppUser admin = appUserDao.findByUsername(ADMIN_USERNAME).orElseThrow();
        AppUser raceAdmin = appUserDao.findByUsername(RACE_USERNAME).orElseThrow();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> demoteRaceAdmin =
                    () -> {
                        start.await();
                        return userAccountService.adminSetRole(
                                admin.getId(), raceAdmin.getId(), "USER", "竞态降级-降对方");
                    };
            Callable<Object> demoteBootstrapAdmin =
                    () -> {
                        start.await();
                        return userAccountService.adminSetRole(
                                raceAdmin.getId(), admin.getId(), "USER", "竞态降级-降回对方");
                    };
            Future<Object> first = pool.submit(demoteRaceAdmin);
            Future<Object> second = pool.submit(demoteBootstrapAdmin);
            start.countDown();

            int successes = 0;
            int conflicts = 0;
            for (Future<Object> future : List.of(first, second)) {
                try {
                    Object result = future.get(45, TimeUnit.SECONDS);
                    assertThat(result).isInstanceOf(AdminUserView.class);
                    successes++;
                } catch (ExecutionException execution) {
                    // 并发降级的败者必须收到 40900 业务异常，而不是唯一键冲突冒 500。
                    assertThat(execution.getCause()).isInstanceOf(BusinessException.class);
                    BusinessException business = (BusinessException) execution.getCause();
                    assertThat(business.apiCode().code()).isEqualTo(40900);
                    assertThat(business.getMessage()).contains("不能降级最后一个管理员");
                    conflicts++;
                }
            }
            assertThat(successes).as("两个并发降级必须恰好成功一个").isEqualTo(1);
            assertThat(conflicts).as("另一个必须被最后管理员锁拒绝").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(activeAdminCount()).as("任何时刻都不可能出现 0 管理员状态").isEqualTo(1);
    }

    /** [AC-5] 锁行为对照（串行）：降掉一人后再降最后一名管理员，锁查询读到提交后的最新计数 1，拒绝。 */
    @Test
    void sequentialSecondDemotionIsRejected_ac5() {
        AppUser admin = appUserDao.findByUsername(ADMIN_USERNAME).orElseThrow();
        AppUser raceAdmin = appUserDao.findByUsername(RACE_USERNAME).orElseThrow();

        userAccountService.adminSetRole(admin.getId(), raceAdmin.getId(), "USER", "先降");
        assertThat(activeAdminCount()).isEqualTo(1);

        // 剩余管理员恰为 admin：再次发起降级必须被行锁后的最新计数拒绝。
        assertThatThrownBy(
                        () ->
                                userAccountService.adminSetRole(
                                        raceAdmin.getId(), admin.getId(), "USER", "再降"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能降级最后一个管理员")
                .extracting(e -> ((BusinessException) e).apiCode())
                .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT);
    }

    /**
     * 统计启用且未删除的管理员数量。
     *
     * @return 数量
     */
    private int activeAdminCount() {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM users"
                                + " WHERE role = 'ADMIN' AND enabled = b'1' AND deleted_at IS NULL",
                        Integer.class);
        return count == null ? 0 : count;
    }
}
