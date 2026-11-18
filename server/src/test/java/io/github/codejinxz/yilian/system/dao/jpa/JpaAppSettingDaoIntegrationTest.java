package io.github.codejinxz.yilian.system.dao.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.support.TestDatabases;
import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 系统配置并发首写集成测试（真实 MySQL yilian_test）：两个线程对同一新键并发 upsert 必须 在数据库层原子收敛——双方都不抛异常、只落一行、值取其一。回退实现（同事务
 * catch DIVE 后重读重写） 会因 rollback-only 事务在这里抛 500，本测试即其回归门；末写版本递增与描述保留也在此一并锁定。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false"
        })
@ActiveProfiles("test")
class JpaAppSettingDaoIntegrationTest {

    /** 测试专用键，前后清理；不触碰 llm.tutor 等真实键。 */
    private static final String KEY = "llm.tutor.it-r1";

    private static final String VALUE_A = "{\"schemaVersion\":1,\"enabled\":true}";
    private static final String VALUE_B = "{\"schemaVersion\":1,\"enabled\":false}";

    @Autowired private AppSettingService appSettingService;

    @Autowired private JdbcTemplate jdbcTemplate;

    static {
        TestDatabases.ensureDatabase();
    }

    @BeforeEach
    @AfterEach
    void cleanTestKey() {
        jdbcTemplate.update("DELETE FROM app_settings WHERE `key` = ?", KEY);
    }

    /** 真双线程并发首写同键：无异常收敛为单行，值取其一（回退式实现在此抛 500）。 */
    @Test
    @DisplayName("并发首写同键原子收敛为单行")
    void concurrentFirstUpsertConvergesToSingleRow_r1() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> upsertAfterBarrier(start, VALUE_A));
            Future<String> second = pool.submit(() -> upsertAfterBarrier(start, VALUE_B));
            start.countDown();
            // 任一线程抛异常（含 rollback-only 的 500）都会在这里显式失败。
            String valueA = first.get(15, TimeUnit.SECONDS);
            String valueB = second.get(15, TimeUnit.SECONDS);

            assertThat(valueA).isEqualTo(VALUE_A);
            assertThat(valueB).isEqualTo(VALUE_B);

            List<Map<String, Object>> rows =
                    jdbcTemplate.queryForList(
                            "SELECT `key`, value, version FROM app_settings WHERE `key` = ?", KEY);
            assertThat(rows).hasSize(1);
            // JSON 列入库会规范化（键排序重排），按语义断言而不是原文比对。
            assertThat(enabledOf((String) rows.get(0).get("value"))).isIn(true, false);
            assertThat(((Number) rows.get(0).get("version")).intValue()).isBetween(1, 2);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 顺序覆盖：version 递增、value 末写胜出、首写 description 保留。 */
    @Test
    @DisplayName("顺序覆盖递增版本并保留首写描述")
    void sequentialUpsertsBumpVersionAndKeepDescription_r1() {
        appSettingService.upsert(KEY, VALUE_A, "首写描述", 1L);
        appSettingService.upsert(KEY, VALUE_B, "覆盖时不应生效的描述", 1L);

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT value, version, description FROM app_settings WHERE `key` = ?",
                        KEY);
        // JSON 列规范化后与写入原文逐字节不同，解析后断言 enabled 语义。
        assertThat(enabledOf((String) row.get("value"))).isFalse();
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(2);
        assertThat((String) row.get("description")).isEqualTo("首写描述");
    }

    /**
     * 解析 app_settings 值 JSON 的 enabled 字段（与 TutorServiceImpl 的 fail-closed 解析同构）。
     *
     * @param json 数据库读回的 JSON 文本
     * @return enabled 布尔值
     */
    private static boolean enabledOf(String json) {
        return new ObjectMapper().readTree(json).get("enabled").asBoolean();
    }

    /**
     * 等栅栏后并发写入；栅栏保证两个事务在时间上重叠，制造真实首写竞态。
     *
     * @param start 启动栅栏
     * @param value 写入值
     * @return 写入值（未抛异常即成功）
     */
    private String upsertAfterBarrier(CountDownLatch start, String value) {
        try {
            start.await(15, TimeUnit.SECONDS);
            appSettingService.upsert(KEY, value, "题目答疑开关（017）", 1L);
            return value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
