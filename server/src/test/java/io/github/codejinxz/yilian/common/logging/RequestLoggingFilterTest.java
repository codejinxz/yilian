package io.github.codejinxz.yilian.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 请求日志的模块映射：按 URI 路径段前缀归类，边界不完整时回落到 system。 */
class RequestLoggingFilterTest {

    /** 每个 URI 前缀都必须命中声明的模块名，防止新增前缀时漏配或映射到枚举外的名字。 */
    @Test
    void mapsEachKnownPrefix() {
        assertThat(RequestLoggingFilter.moduleOf("/api/auth")).isEqualTo("account");
        assertThat(RequestLoggingFilter.moduleOf("/api/admin")).isEqualTo("admin");
        assertThat(RequestLoggingFilter.moduleOf("/api/content")).isEqualTo("content");
        assertThat(RequestLoggingFilter.moduleOf("/api/practice")).isEqualTo("practice");
        assertThat(RequestLoggingFilter.moduleOf("/api/ai")).isEqualTo("ai");
        assertThat(RequestLoggingFilter.moduleOf("/api/analytics")).isEqualTo("analytics");
        assertThat(RequestLoggingFilter.moduleOf("/actuator")).isEqualTo("system");
    }

    /** 映射必须覆盖完整子路径：日志检索按模块聚合，深层接口漏归模块会低估该模块的流量与错误。 */
    @Test
    void mapsNestedPaths() {
        assertThat(RequestLoggingFilter.moduleOf("/api/auth/login")).isEqualTo("account");
        assertThat(RequestLoggingFilter.moduleOf("/api/admin/users/42")).isEqualTo("admin");
        assertThat(RequestLoggingFilter.moduleOf("/api/content/sentences/1")).isEqualTo("content");
        assertThat(RequestLoggingFilter.moduleOf("/api/practice/attempts")).isEqualTo("practice");
        assertThat(RequestLoggingFilter.moduleOf("/api/ai/grade")).isEqualTo("ai");
        assertThat(RequestLoggingFilter.moduleOf("/api/analytics/summary")).isEqualTo("analytics");
        assertThat(RequestLoggingFilter.moduleOf("/actuator/health")).isEqualTo("system");
    }

    /** 结尾斜杠只是同一资源的另一种写法，不能因写法差异被拆成两个模块。 */
    @Test
    void treatsTrailingSlashAsSameModule() {
        assertThat(RequestLoggingFilter.moduleOf("/api/auth/")).isEqualTo("account");
        assertThat(RequestLoggingFilter.moduleOf("/actuator/")).isEqualTo("system");
    }

    /** 前缀比较必须按路径段边界做，否则 {@code /api/auth} 会误吞 {@code /api/authentication} 之类的更长路径。 */
    @Test
    void requiresPathSegmentBoundary() {
        assertThat(RequestLoggingFilter.moduleOf("/api/authentication")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf("/api/adminx")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf("/api/contents")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf("/api/practices")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf("/api/aim")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf("/api/analyticsx")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf("/actuatorx")).isEqualTo("system");
    }

    /** 未命中任何前缀或路径缺失时兜底为 system，保证每条请求日志都有可检索的模块字段。 */
    @Test
    void fallsBackToSystemForUnknownOrMissingPath() {
        assertThat(RequestLoggingFilter.moduleOf("/api/unknown")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf("/")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf("")).isEqualTo("system");
        assertThat(RequestLoggingFilter.moduleOf(null)).isEqualTo("system");
    }
}
