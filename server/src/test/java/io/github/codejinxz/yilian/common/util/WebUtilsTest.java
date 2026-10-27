package io.github.codejinxz.yilian.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** WebUtils 的客户端 IP 提取：只信任 remoteAddr，可伪造的 X-Forwarded-For 一律忽略。 */
class WebUtilsTest {

    @Test
    void nullRequestFallsBackToUnknown() {
        assertThat(WebUtils.clientIp(null)).isEqualTo("unknown");
    }

    @Test
    void returnsRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        assertThat(WebUtils.clientIp(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void trimsRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(" 192.0.2.10 ");

        assertThat(WebUtils.clientIp(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void ignoresForwardedForHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178");

        assertThat(WebUtils.clientIp(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void ignoresForwardedForWhenRemoteAddrIsBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("   ");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(WebUtils.clientIp(request)).isEqualTo("unknown");
    }

    @Test
    void ignoresForwardedForWhenRemoteAddrIsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(WebUtils.clientIp(request)).isEqualTo("unknown");
    }

    @Test
    void blankRemoteAddrFallsBackToUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("\t");

        assertThat(WebUtils.clientIp(request)).isEqualTo("unknown");
    }

    @Test
    void nullRemoteAddrFallsBackToUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(WebUtils.clientIp(request)).isEqualTo("unknown");
    }
}
