package io.github.codejinxz.yilian.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等归属者隔离的端到端单元测试：真实切面 + 真实 JDBC 服务 + 内存版唯一约束。
 *
 * <p>内存实现只模拟 {@code idempotency_record} 的三条 SQL 及其唯一键语义，用来验证两用户同键不会互相占位、 同用户同作用域重复返回
 * 409，以及释放只删除自己的记录。
 */
class IdempotencyOwnerIsolationTest {

    private static final String ATTEMPT_ID = "attempt-1";

    private final InMemoryJdbcTemplate jdbcTemplate = new InMemoryJdbcTemplate();
    private final IdempotencyAspect aspect =
            new IdempotencyAspect(new JdbcIdempotencyService(jdbcTemplate));

    /** 每个用例都从匿名、带客户端 IP 的干净上下文开始。 */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/attempts");
        request.setRemoteAddr("203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    /** 两个用户使用同一个键、不同作用域时都可以执行，不会互相占位。 */
    @Test
    void twoUsersSameKeyDifferentScopesBothExecute() throws Throwable {
        authenticate(1L);
        assertThat(execute("submit")).isEqualTo("ok");

        authenticate(2L);
        assertThat(execute("review")).isEqualTo("ok");

        assertThat(jdbcTemplate.storedCount()).isEqualTo(2);
    }

    /** 两个用户使用同一个键与同一个作用域时也都可以执行，归属者必须参与唯一键。 */
    @Test
    void twoUsersSameKeySameScopeBothExecute() throws Throwable {
        authenticate(1L);
        assertThat(execute("submit")).isEqualTo("ok");

        authenticate(2L);
        assertThat(execute("submit")).isEqualTo("ok");

        assertThat(jdbcTemplate.storedCount()).isEqualTo(2);
    }

    /** 同一用户在同一作用域重复使用同一个键时，第二次返回 409 且不执行业务方法。 */
    @Test
    void sameUserSameScopeDuplicateReturnsConflict() throws Throwable {
        authenticate(1L);
        assertThat(execute("submit")).isEqualTo("ok");

        ProceedingJoinPoint duplicate = joinPoint("submit");
        when(duplicate.proceed()).thenReturn("ok");
        assertThatThrownBy(() -> aspect.around(duplicate, annotation("submit")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT));

        verify(duplicate, never()).proceed();
        assertThat(jdbcTemplate.storedCount()).isEqualTo(1);
    }

    /** 同一用户在不同作用域下可以复用同一个键，作用域必须参与唯一键。 */
    @Test
    void sameUserSameKeyDifferentScopesBothExecute() throws Throwable {
        authenticate(1L);
        assertThat(execute("submit")).isEqualTo("ok");
        assertThat(execute("review")).isEqualTo("ok");

        assertThat(jdbcTemplate.storedCount()).isEqualTo(2);
    }

    /** 用户 A 失败释放时只删除自己的占位，用户 B 的同键记录仍然有效。 */
    @Test
    void releaseOnlyDeletesOwnRecord() throws Throwable {
        authenticate(2L);
        assertThat(execute("submit")).isEqualTo("ok");

        authenticate(1L);
        ProceedingJoinPoint failing = joinPoint("submit");
        when(failing.proceed()).thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> aspect.around(failing, annotation("submit")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        authenticate(1L);
        assertThat(execute("submit")).isEqualTo("ok");

        authenticate(2L);
        ProceedingJoinPoint duplicateOfUserB = joinPoint("submit");
        when(duplicateOfUserB.proceed()).thenReturn("ok");
        assertThatThrownBy(() -> aspect.around(duplicateOfUserB, annotation("submit")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IDEMPOTENT_CONFLICT));

        verify(duplicateOfUserB, never()).proceed();
        assertThat(jdbcTemplate.storedCount()).isEqualTo(2);
    }

    private Object execute(String methodName) throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint(methodName);
        when(joinPoint.proceed()).thenReturn("ok");
        return aspect.around(joinPoint, annotation(methodName));
    }

    private static void authenticate(long userId) {
        YilianUserPrincipal principal =
                YilianUserPrincipal.of(userId, "user-" + userId, "USER", true);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal, "token", principal.getAuthorities()));
    }

    private static ProceedingJoinPoint joinPoint(String methodName) throws NoSuchMethodException {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = Sample.class.getDeclaredMethod(methodName, AttemptRequest.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs())
                .thenReturn(new Object[] {new AttemptRequest(ATTEMPT_ID, "answer")});
        return joinPoint;
    }

    private static Idempotent annotation(String methodName) throws NoSuchMethodException {
        return Sample.class
                .getDeclaredMethod(methodName, AttemptRequest.class)
                .getAnnotation(Idempotent.class);
    }

    /** 模拟答题提交请求；同一个 attemptId 在不同用户、不同作用域下的唯一性由幂等组件判定。 */
    record AttemptRequest(String attemptId, String answer) {}

    /** 供反射读取注解的测试载体；两个方法使用不同的业务作用域。 */
    static class Sample {

        @Idempotent(scope = "attempt-submit", key = "#request.attemptId()")
        void submit(AttemptRequest request) {
            // 仅用于测试注解元数据。
        }

        @Idempotent(scope = "attempt-review", key = "#request.attemptId()")
        void review(AttemptRequest request) {
            // 仅用于测试注解元数据。
        }
    }

    /** 记录的唯一键，与迁移后的 {@code (owner_id, scope, idempotency_key)} 一致。 */
    private record RowKey(String owner, String scope, String key) {}

    /** 内存中的占位行，保留请求摘要与过期时间供删除语句判断。 */
    private record StoredRow(String requestHash, Instant expiresAt) {}

    /**
     * 用内存表模拟 {@code idempotency_record} 的唯一约束与三条 SQL。
     *
     * <p>测试不依赖本机 MySQL，也能覆盖「插入冲突 → 清理过期 → 重试」与「按归属者释放」的真实分支； SQL 只按语义识别，避免把完整语句复制到测试里重复维护。
     */
    private static final class InMemoryJdbcTemplate extends JdbcTemplate {

        private final Map<RowKey, StoredRow> rows = new ConcurrentHashMap<>();

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO idempotency_record")) {
                return insert(args);
            }
            if (sql.contains("expires_at <= ?")) {
                return deleteExpired(args);
            }
            if (sql.contains("request_hash = ?")) {
                return deleteOwned(args);
            }
            throw new AssertionError("未预期的幂等 SQL: " + sql);
        }

        int storedCount() {
            return rows.size();
        }

        private int insert(Object[] args) {
            RowKey key = rowKey(args);
            StoredRow row = new StoredRow((String) args[3], ((Timestamp) args[5]).toInstant());
            if (rows.putIfAbsent(key, row) != null) {
                throw new DuplicateKeyException("幂等键已存在");
            }
            return 1;
        }

        private int deleteExpired(Object[] args) {
            RowKey key = rowKey(args);
            StoredRow existing = rows.get(key);
            Instant now = ((Timestamp) args[3]).toInstant();
            if (existing == null || existing.expiresAt().isAfter(now)) {
                return 0;
            }
            rows.remove(key, existing);
            return 1;
        }

        private int deleteOwned(Object[] args) {
            RowKey key = rowKey(args);
            StoredRow existing = rows.get(key);
            if (existing == null || !existing.requestHash().equals(args[3])) {
                return 0;
            }
            rows.remove(key, existing);
            return 1;
        }

        private static RowKey rowKey(Object[] args) {
            return new RowKey((String) args[0], (String) args[1], (String) args[2]);
        }
    }
}
