package io.github.codejinxz.yilian.common.logging;

import io.github.codejinxz.yilian.common.storage.ObjectStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计/异常日志的分区维护：预建未来月份，按保留期 DROP PARTITION。
 *
 * <p>两张表按 {@code created_at} 月分区；应用层不做行级 DELETE。任务可重入：分区已存在时跳过，
 * 单表失败只记日志，不阻塞调度。保留期按“整月粒度”执行：只有分区上界早于截止日才删除。
 *
 * <p>过期幂等占位记录的清理也并入本类：{@code idempotency_record} 未分区， 只能行级 DELETE；复用同一调度入口避免另起炉灶。
 *
 * <p>分片上传会话的过期回收同样并入： 本地存储的会话映射在内存、分片在临时目录，废弃后无人回收会持续泄漏； 云实现的默认方法是无操作，由其生命周期规则管理。
 *
 * <p>spring_session_attributes 的孤儿 BLOB 回收也并入（V11）：V10 删除其物理外键（含 ON DELETE
 * CASCADE）后，会话行被删而属性行残留时无自动回收。清理是反连接 DELETE （属性行 LEFT JOIN 会话行，无命中即孤儿），可重入、失败只记日志；索引无需新增—— 属性行 PK
 * 左前缀即驱动侧扫描键，会话行 PK 即被探测侧等值键（V11 迁移注释有裁决）。
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);

    private static final int FUTURE_MONTHS = 3;

    /** 幂等记录单批删除上限：限制单条 DELETE 的持锁时长与 binlog 压力，循环清到批不满即止。 */
    private static final int IDEMPOTENCY_PURGE_BATCH_SIZE = 500;

    /** 幂等记录单轮最大批数（500 × 200 = 10 万行）：异常积压时分轮清理，不拖死调度线程； 剩余部分等下一个半周期继续，清理本身永远追得上（幂等记录 24h 过期）。 */
    private static final int IDEMPOTENCY_PURGE_MAX_BATCHES = 200;

    /**
     * 过期判定沿用 {@code JdbcIdempotencyService} 的 {@code expires_at <= ?} 语义：
     * 恰好等于截止时间的记录视为过期，与「冲突时惰性清理」口径一致。
     */
    private static final String PURGE_EXPIRED_IDEMPOTENCY_SQL =
            "DELETE FROM idempotency_record WHERE expires_at <= ? LIMIT "
                    + IDEMPOTENCY_PURGE_BATCH_SIZE;

    /**
     * 孤儿会话属性行清理（V11）：属性行在 spring_session 里找不到归属会话即删。 反连接驱动侧是 spring_session_attributes（PK
     * 左前缀覆盖扫描），被探测侧 spring_session 走 PK 等值； 不加 LIMIT——多表 DELETE 语法不支持，且孤儿只在会话行被异常删除时零星产生，单轮全量
     * 规模受会话总量上界约束（半小时级过期回收持续压表），不会失控。
     */
    static final String PURGE_ORPHAN_SESSION_ATTRIBUTES_SQL =
            "DELETE a FROM spring_session_attributes a"
                    + " LEFT JOIN spring_session s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID"
                    + " WHERE s.PRIMARY_ID IS NULL";

    /** 分片会话保鲜期：超过 24 小时未完成的会话视为废弃。 */
    static final Duration MULTIPART_SESSION_TTL = Duration.ofHours(24);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorage objectStorage;
    private final int auditRetentionDays;
    private final int errorRetentionDays;

    /**
     * 注入数据源、对象存储与保留期配置。
     *
     * @param jdbcTemplate Spring JDBC 模板
     * @param objectStorage 对象存储（分片会话清理委托给实现，云实现默认无操作）
     * @param auditRetentionDays 操作日志保留天数，默认 365
     * @param errorRetentionDays 异常日志保留天数，默认 30
     */
    public PartitionMaintenanceJob(
            JdbcTemplate jdbcTemplate,
            ObjectStorage objectStorage,
            @Value("${yilian.log.operation-retention-days:365}") int auditRetentionDays,
            @Value("${yilian.log.error-retention-days:30}") int errorRetentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectStorage = objectStorage;
        this.auditRetentionDays = auditRetentionDays;
        this.errorRetentionDays = errorRetentionDays;
    }

    /** 每天凌晨维护一次；任何一张表失败只记日志，下次调度重试。 */
    @Scheduled(cron = "${yilian.log.partition-maintenance-cron:0 20 3 * * *}")
    public void maintain() {
        try {
            maintainTable("admin_audit_log", auditRetentionDays);
        } catch (RuntimeException exception) {
            log.error("failed to maintain admin_audit_log partitions", exception);
        }
        try {
            maintainTable("error_log", errorRetentionDays);
        } catch (RuntimeException exception) {
            log.error("failed to maintain error_log partitions", exception);
        }
    }

    /**
     * 每半小时清理一次过期幂等占位记录。
     *
     * <p>可重入性三重保证：{@code fixedDelay} 串行调度不与自身重叠；DELETE 本身幂等， 即便被并发触发
     * （如未来多入口调用）也只是多删一次空集，不会报错；分批循环以「批不满」收敛， 单轮有最大批数兜底。 失败只记日志，下个周期重试。
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 10 * 60 * 1000L)
    public void purgeExpiredIdempotencyRecords() {
        try {
            long total = 0;
            for (int batch = 0; batch < IDEMPOTENCY_PURGE_MAX_BATCHES; batch++) {
                int removed =
                        jdbcTemplate.update(
                                PURGE_EXPIRED_IDEMPOTENCY_SQL, Timestamp.from(Instant.now()));
                total += removed;
                if (removed < IDEMPOTENCY_PURGE_BATCH_SIZE) {
                    break;
                }
            }
            if (total > 0) {
                log.info("purged {} expired idempotency records", total);
            }
        } catch (RuntimeException exception) {
            log.error("failed to purge expired idempotency records", exception);
        }
    }

    /**
     * 每小时回收一次超过 24h 的分片上传会话：内存映射与磁盘临时分片一并清理。
     *
     * <p>可重入：清理本身幂等（移除映射/删目录对已消失资源是空操作）；fixedDelay 串行调度不自我叠加； 失败只记日志，下个周期重试。
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 20 * 60 * 1000L)
    public void cleanupExpiredMultipartSessions() {
        try {
            int removed =
                    objectStorage.cleanupExpiredMultipartSessions(
                            Instant.now().minus(MULTIPART_SESSION_TTL));
            if (removed > 0) {
                log.info("cleaned up {} expired multipart upload sessions", removed);
            }
        } catch (RuntimeException exception) {
            log.error("failed to cleanup expired multipart upload sessions", exception);
        }
    }

    /**
     * 每小时清理一次 spring_session_attributes 的孤儿属性行（V11）。
     *
     * <p>可重入：DELETE 幂等，对不存在孤儿是空操作；fixedDelay 串行调度不自我叠加。失败只记日志， 下个周期重试。与分片会话清扫同频（小时级）：两者都是
     * 「异常残留的兜底清扫」，常态零工作量。
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 25 * 60 * 1000L)
    public void purgeOrphanSessionAttributes() {
        try {
            int removed = jdbcTemplate.update(PURGE_ORPHAN_SESSION_ATTRIBUTES_SQL);
            if (removed > 0) {
                log.info("purged {} orphan spring_session_attributes rows", removed);
            }
        } catch (RuntimeException exception) {
            log.error("failed to purge orphan spring_session_attributes rows", exception);
        }
    }

    /**
     * 单表维护：先补未来分区，再删除过期分区。
     *
     * @param table 表名，只允许内部常量
     * @param retentionDays 保留天数
     */
    private void maintainTable(String table, int retentionDays) {
        Map<YearMonth, String> partitions = loadPartitions(table);
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        List<YearMonth> missing = new ArrayList<>();
        for (int offset = 1; offset <= FUTURE_MONTHS; offset++) {
            YearMonth month = current.plusMonths(offset);
            if (!partitions.containsKey(month)) {
                missing.add(month);
            }
        }
        if (!missing.isEmpty()) {
            createFuturePartitions(table, partitions, missing);
        }
        dropExpiredPartitions(table, partitions, retentionDays);
    }

    /**
     * 读取月分区名称与上界天数；p_future 的 description 为 MAXVALUE，无法解析，忽略。
     *
     * @param table 表名
     * @return 月份到分区名的映射
     */
    private Map<YearMonth, String> loadPartitions(String table) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT PARTITION_NAME, PARTITION_DESCRIPTION FROM information_schema.PARTITIONS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                                + " AND PARTITION_NAME IS NOT NULL",
                        table);
        Map<YearMonth, String> partitions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("PARTITION_NAME"));
            if (!name.startsWith("p") || "p_future".equals(name)) {
                continue;
            }
            try {
                YearMonth month =
                        YearMonth.parse(
                                name.substring(1),
                                java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
                partitions.put(month, name);
            } catch (RuntimeException ignored) {
                // 非本任务命名的分区不参与计算。
            }
        }
        return partitions;
    }

    /**
     * 把 p_future 重组为“缺失月份 + p_future”，避免插入落到 MAXVALUE 后无法再 ADD PARTITION。
     *
     * @param table 表名
     * @param partitions 已有分区
     * @param missing 待补月份，按升序
     */
    private void createFuturePartitions(
            String table, Map<YearMonth, String> partitions, List<YearMonth> missing) {
        if (!hasFuturePartition(table)) {
            // 没有 p_future 时不冒险重建，留给人工处理。
            log.warn("skip partition creation for {}: p_future missing", table);
            return;
        }
        StringBuilder sql =
                new StringBuilder("ALTER TABLE ")
                        .append(table)
                        .append(" REORGANIZE PARTITION p_future INTO (");
        List<String> definitions = new ArrayList<>();
        for (YearMonth month : missing) {
            String name =
                    "p" + month.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            definitions.add(
                    "PARTITION "
                            + name
                            + " VALUES LESS THAN (TO_DAYS('"
                            + month.plusMonths(1).atDay(1)
                            + "'))");
        }
        definitions.add("PARTITION p_future VALUES LESS THAN MAXVALUE");
        sql.append(String.join(", ", definitions)).append(")");
        jdbcTemplate.execute(sql.toString());
        log.info("created {} partitions for {}", missing.size(), table);
    }

    private boolean hasFuturePartition(String table) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.PARTITIONS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                                + " AND PARTITION_NAME = 'p_future'",
                        Integer.class,
                        table);
        return count != null && count > 0;
    }

    /**
     * 删除上界早于保留截止日的整月分区；不做行级 DELETE。
     *
     * @param table 表名
     * @param partitions 已有分区
     * @param retentionDays 保留天数
     */
    private void dropExpiredPartitions(
            String table, Map<YearMonth, String> partitions, int retentionDays) {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
        for (Map.Entry<YearMonth, String> entry : partitions.entrySet()) {
            LocalDate upperBound = entry.getKey().plusMonths(1).atDay(1);
            if (!upperBound.isAfter(cutoff)) {
                try {
                    jdbcTemplate.execute(
                            "ALTER TABLE " + table + " DROP PARTITION " + entry.getValue());
                    log.info("dropped expired partition {} from {}", entry.getValue(), table);
                } catch (RuntimeException exception) {
                    log.error(
                            "failed to drop partition {} from {}",
                            entry.getValue(),
                            table,
                            exception);
                }
            }
        }
    }
}
