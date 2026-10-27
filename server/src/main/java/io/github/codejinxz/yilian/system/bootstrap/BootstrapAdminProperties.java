package io.github.codejinxz.yilian.system.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * bootstrap 管理员配置（前缀 {@code yilian.bootstrap-admin}）。
 *
 * <p>username / password 允许为空串，非法组合由服务实现在启动时判定：local 可回落到默认密码， prod 缺失或仍为默认值必须让启动失败。
 */
@ConfigurationProperties(prefix = "yilian.bootstrap-admin")
public record BootstrapAdminProperties(
        boolean enabled, String username, String password, boolean resetPasswordOnStartup) {

    /** 文档化的本地默认密码；prod 使用该值会被拒绝启动。 */
    public static final String DEFAULT_PASSWORD = "change-me-now";

    /** 归一化外部配置：用户名去首尾空白，密码保持原样（空白本身也是需要识别的非法值）。 */
    public BootstrapAdminProperties {
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
    }
}
