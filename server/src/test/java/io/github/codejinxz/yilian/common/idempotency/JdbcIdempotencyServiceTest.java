package io.github.codejinxz.yilian.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

/** {@link JdbcIdempotencyService} 的归属者隔离、唯一键冲突、过期重试与释放。 */
class JdbcIdempotencyServiceTest {

    private static final IdempotencyRequest REQUEST =
            new IdempotencyRequest("42", "attempt-submit", "attempt-1", "hash-1", 3600);

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcIdempotencyService service = new JdbcIdempotencyService(jdbcTemplate);

    /** 首次插入成功即占用成功，并把归属者、作用域与键一起写入。 */
    @Test
    void firstInsertAcquiresWithOwnerScopeAndKey() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(service.tryAcquire(REQUEST)).isTrue();

        verify(jdbcTemplate)
                .update(
                        contains("INSERT INTO idempotency_record"),
                        eq("42"),
                        eq("attempt-submit"),
                        eq("attempt-1"),
                        eq("hash-1"),
                        any(Timestamp.class),
                        any(Timestamp.class));
    }

    /** 唯一键冲突且记录未过期时返回 false，由切面翻译成 409 而不是 500。 */
    @Test
    void duplicateKeyReturnsFalse() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(0);

        assertThat(service.tryAcquire(REQUEST)).isFalse();

        verify(jdbcTemplate)
                .update(
                        contains("owner_id = ? AND scope = ? AND idempotency_key = ?"),
                        eq("42"),
                        eq("attempt-submit"),
                        eq("attempt-1"),
                        any(Timestamp.class));
    }

    /** 唯一键冲突但记录已过期时，删除该归属者、作用域下的过期行后再插入一次。 */
    @Test
    void expiredRecordIsReplaced() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(1)
                .thenReturn(1);

        assertThat(service.tryAcquire(REQUEST)).isTrue();
    }

    /** 业务失败释放时只删除同一归属者、作用域、键与 request_hash 的记录。 */
    @Test
    void releaseDeletesOnlyMatchingOwnerScopeAndHash() {
        service.release(REQUEST);

        verify(jdbcTemplate)
                .update(
                        "DELETE FROM idempotency_record"
                                + " WHERE owner_id = ? AND scope = ? AND idempotency_key = ?"
                                + " AND request_hash = ?",
                        "42",
                        "attempt-submit",
                        "attempt-1",
                        "hash-1");
    }
}
