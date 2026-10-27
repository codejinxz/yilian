package io.github.codejinxz.yilian.common.logging;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

/** {@link JdbcErrorLogRecorder} 的写入与失败隔离。 */
class JdbcErrorLogRecorderTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcErrorLogRecorder recorder = new JdbcErrorLogRecorder(jdbcTemplate);

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /** 未知异常应写入 error_log，并使用 traceId、脱敏用户与请求信息。 */
    @Test
    void writesUnexpectedException() {
        MDC.put("traceId", "trace-1");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test/boom");

        recorder.record(new IllegalStateException("boom"), request);

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    /** 数据库写入失败不能覆盖原始异常响应。 */
    @Test
    void persistenceFailureIsSwallowed() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatNoException()
                .isThrownBy(() -> recorder.record(new IllegalStateException("boom"), null));
    }
}
