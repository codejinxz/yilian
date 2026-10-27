package io.github.codejinxz.yilian.common.logging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** {@link UnexpectedExceptionLoggingFilter} 的异常捕获与再抛出。 */
class UnexpectedExceptionLoggingFilterTest {

    private final ErrorLogRecorder recorder = mock(ErrorLogRecorder.class);
    private final UnexpectedExceptionLoggingFilter filter =
            new UnexpectedExceptionLoggingFilter(recorder);

    /** 过滤链异常应写入 error_log 后原样抛出。 */
    @Test
    void recordsAndRethrowsFilterException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain =
                (req, res) -> {
                    throw new ServletException("boom");
                };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class)
                .hasMessage("boom");
        verify(recorder)
                .record(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(request));
    }
}
