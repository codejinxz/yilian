package io.github.codejinxz.yilian.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** {@link JdbcOperationLogRecorder} 的写入、审计字段落库与失败隔离。 */
class JdbcOperationLogRecorderTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcOperationLogRecorder recorder = new JdbcOperationLogRecorder(jdbcTemplate);

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    /** 审计对象、原因与操作者快照应进入 INSERT 列和参数，且不影响原有字段顺序。 */
    @Test
    void writesAuditTargetAndReason() {
        MDC.put("traceId", "trace-operation");
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/admin/attempts/att-42/original");
        request.setRemoteAddr("192.0.2.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.record(
                annotation("viewOriginal"),
                "attempt",
                "att-42",
                "support-ticket-7",
                "success",
                12L,
                null,
                420L,
                "admin",
                "ROLE_ADMIN");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());

        assertThat(sqlCaptor.getValue())
                .contains("target_type, target_id, reason")
                .contains("VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        Object[] values = argsCaptor.getValue();
        assertThat(values).hasSize(14);
        assertThat(values[0]).isEqualTo("trace-operation");
        assertThat(values[1]).isEqualTo("admin");
        assertThat(values[2]).isEqualTo(420L);
        assertThat(values[3]).isEqualTo("admin");
        assertThat(values[4]).isEqualTo("ROLE_ADMIN");
        assertThat(values[5]).isEqualTo("192.0.2.10");
        assertThat(values[6]).isEqualTo("view-original");
        assertThat(values[7]).isEqualTo("attempt");
        assertThat(values[8]).isEqualTo("att-42");
        assertThat(values[9]).isEqualTo("support-ticket-7");
        assertThat(values[10]).isEqualTo("SUCCESS");
        assertThat(values[11]).isEqualTo(12);
        assertThat(values[12]).isEqualTo("POST");
        assertThat(values[13]).isEqualTo("/api/admin/attempts/att-42/original");
    }

    /** login/logout 等未声明审计对象的操作仍写入，审计对象三列以 NULL 落库，操作者回退 unknown/SYSTEM。 */
    @Test
    void writesNullWhenAuditTargetIsNotDeclared() {
        MDC.put("traceId", "trace-logout");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(
                        new MockHttpServletRequest("POST", "/api/auth/logout")));

        recorder.record(annotation("logout"), "", "", "", "success", 1L, null, null, null, null);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), argsCaptor.capture());

        Object[] values = argsCaptor.getValue();
        assertThat(values).hasSize(14);
        assertThat(values[1]).isEqualTo("account");
        assertThat(values[2]).isNull();
        assertThat(values[3]).isEqualTo("unknown");
        assertThat(values[4]).isEqualTo("SYSTEM");
        assertThat(values[7]).isNull();
        assertThat(values[8]).isNull();
        assertThat(values[9]).isNull();
    }

    /** 旧注解的 {@code import} 模块必须在落库前映射为 §7 枚举值 {@code content}，否则会违反 CHECK 约束或落入 system。 */
    @Test
    void mapsLegacyImportModuleToContent() {
        MDC.put("traceId", "trace-import");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(
                        new MockHttpServletRequest("POST", "/api/admin/uploads")));

        recorder.record(annotation("upload"), "", "", "", "success", 1L, null, null, null, null);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), argsCaptor.capture());

        assertThat(argsCaptor.getValue()[1]).isEqualTo("content");
    }

    /** 审计表写入失败不能覆盖业务结果。 */
    @Test
    void persistenceFailureIsSwallowed() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatNoException()
                .isThrownBy(
                        () ->
                                recorder.record(
                                        annotation("logout"),
                                        "",
                                        "",
                                        "",
                                        "success",
                                        1L,
                                        null,
                                        null,
                                        null,
                                        null));
    }

    /**
     * 按方法名读取测试注解。
     *
     * @param methodName 测试载体方法名
     * @return 方法上的操作日志注解
     */
    private static OperationLog annotation(String methodName) {
        try {
            return Sample.class.getDeclaredMethod(methodName).getAnnotation(OperationLog.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 供反射读取注解的测试载体。 */
    static class Sample {

        @OperationLog(module = "auth", action = "logout")
        void logout() {
            // 仅用于测试注解元数据。
        }

        @OperationLog(module = "import", action = "upload")
        void upload() {
            // 仅用于测试旧模块名 import 的归一映射。
        }

        @OperationLog(
                module = "admin",
                action = "view-original",
                targetType = "attempt",
                targetId = "att-42",
                reason = "support-ticket-7")
        void viewOriginal() {
            // 仅用于测试审计对象与原因的读取。
        }
    }
}
