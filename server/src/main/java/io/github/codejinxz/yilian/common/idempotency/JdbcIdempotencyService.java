package io.github.codejinxz.yilian.common.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 基于 {@code idempotency_record} 唯一约束的幂等占位实现。
 *
 * <p>插入成功即首次请求；捕获 {@link DuplicateKeyException} 后返回 false，由切面翻译成友好业务码， 不让唯一键冲突冒成 500。唯一键由 {@code
 * (owner_id, scope, idempotency_key)} 组成，因此 A 用户占用的键不会挡住 B 用户， 同一用户在不同业务作用域下也不会互相冲突。
 *
 * <p>若重复插入的是已过期但尚未清理的记录，先删除该归属者、作用域下同键的过期行再重试一次； 仍冲突说明并发请求抢先占位，同样按重复处理。
 *
 * <p>数据库不可用时异常向上抛出，采用失败关闭：宁可让请求重试，也不在没有唯一约束保护的情况下继续执行。
 */
@Service
public class JdbcIdempotencyService implements IdempotencyService {

    private static final String INSERT_SQL =
            "INSERT INTO idempotency_record"
                    + " (owner_id, scope, idempotency_key, request_hash, created_at, expires_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";
    private static final String DELETE_EXPIRED_SQL =
            "DELETE FROM idempotency_record"
                    + " WHERE owner_id = ? AND scope = ? AND idempotency_key = ?"
                    + " AND expires_at <= ?";
    private static final String DELETE_SQL =
            "DELETE FROM idempotency_record"
                    + " WHERE owner_id = ? AND scope = ? AND idempotency_key = ?"
                    + " AND request_hash = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 注入共享数据源上的 JDBC 模板。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public JdbcIdempotencyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 尝试插入占位记录，唯一键冲突视为重复请求。
     *
     * <p>冲突后的过期清理同样按归属者、作用域与键定位，避免误删其他用户或其他作用域的记录。
     *
     * @param request 幂等请求
     * @return true 表示本次首次占用；false 表示同一归属者、作用域下键已存在
     */
    @Override
    public boolean tryAcquire(IdempotencyRequest request) {
        try {
            insert(request);
            return true;
        } catch (DuplicateKeyException duplicate) {
            int removed =
                    jdbcTemplate.update(
                            DELETE_EXPIRED_SQL,
                            request.owner(),
                            request.scope(),
                            request.key(),
                            Timestamp.from(Instant.now()));
            if (removed == 0) {
                return false;
            }
            try {
                insert(request);
                return true;
            } catch (DuplicateKeyException race) {
                return false;
            }
        }
    }

    /**
     * 删除本次请求写入的占位记录。
     *
     * <p>同时匹配归属者、作用域、键与 request_hash：既不会删掉其他用户同键的占位，也不会误删同一用户并发写入的另一份请求。
     *
     * @param request 原幂等请求
     */
    @Override
    public void release(IdempotencyRequest request) {
        jdbcTemplate.update(
                DELETE_SQL, request.owner(), request.scope(), request.key(), request.requestHash());
    }

    private void insert(IdempotencyRequest request) {
        Instant now = Instant.now();
        Timestamp createdAt = Timestamp.from(now);
        Timestamp expiresAt = Timestamp.from(now.plusSeconds(request.expiresInSeconds()));
        jdbcTemplate.update(
                INSERT_SQL,
                request.owner(),
                request.scope(),
                request.key(),
                request.requestHash(),
                createdAt,
                expiresAt);
    }
}
