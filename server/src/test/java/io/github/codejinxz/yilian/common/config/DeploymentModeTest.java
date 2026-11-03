package io.github.codejinxz.yilian.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** prod 判定的三态与别名场景（review-verify-2 03#06，AC-2）：profile 含 prod 或显式标记即按 prod 加固。 */
class DeploymentModeTest {

    /** 无 prod profile 且无标记：非 prod（local 语义保留）。 */
    @Test
    void noProfileAndNoMarkerIsNotProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThat(DeploymentMode.isProd(environment)).isFalse();
    }

    /** profile 恰为 prod：判 prod。 */
    @Test
    void prodProfileIsProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThat(DeploymentMode.isProd(environment)).isTrue();
    }

    /** 仅环境标记（profile 是别名或陌生命名）：判 prod——别名部署的兜底通道。 */
    @Test
    void markerAloneIsProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("container");
        environment.setProperty("YILIAN_PROD_MARKER", "true");

        assertThat(DeploymentMode.isProd(environment)).isTrue();
    }

    /** 别名 profile（production）名字含 prod：无标记也判 prod。 */
    @Test
    void prodAliasProfileIsProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThat(DeploymentMode.isProd(environment)).isTrue();
    }

    /** 别名 + 标记叠加：仍判 prod（组合态不互斥、不降级）。 */
    @Test
    void aliasWithMarkerIsProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        environment.setProperty("YILIAN_PROD_MARKER", "TRUE");

        assertThat(DeploymentMode.isProd(environment)).isTrue();
    }

    /** 标记值非 true（含大小写变体以外）：不生效，避免「设了就算」的误判。 */
    @Test
    void markerOtherThanTrueIsIgnored() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("YILIAN_PROD_MARKER", "false");
        environment.setActiveProfiles("dev");

        assertThat(DeploymentMode.isProd(environment)).isFalse();
    }

    /** 无任何激活 profile（如纯默认启动）：不误判 prod。 */
    @Test
    void emptyProfilesWithoutMarkerIsNotProd() {
        assertThat(DeploymentMode.isProd(new MockEnvironment())).isFalse();
    }
}
