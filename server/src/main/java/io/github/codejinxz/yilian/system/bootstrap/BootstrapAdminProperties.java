package io.github.codejinxz.yilian.system.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * bootstrap 管理员配置（前缀 {@code yilian.bootstrap-admin}）。
 *
 * <p>username / password 允许为空串，非法组合由服务实现在启动时判定：local 可回落到默认密码， prod 缺失或仍为默认值必须让启动失败。
 *
 * @param enabled 是否启用 bootstrap 初始化
 * @param username bootstrap 管理员登录名
 * @param password bootstrap 管理员口令
 * @param resetPasswordOnStartup 一次性恢复开关：启动时把口令重置为配置值
 * @param resetMarkerFile 一次性开关的「已应用」标记文件路径（018 AC-3）；存上次生效配置口令的 BCrypt 哈希
 */
@ConfigurationProperties(prefix = "yilian.bootstrap-admin")
public record BootstrapAdminProperties(
        boolean enabled,
        String username,
        String password,
        boolean resetPasswordOnStartup,
        String resetMarkerFile) {

    /** 文档化的本地默认密码；prod 使用该值会被拒绝启动。 */
    public static final String DEFAULT_PASSWORD = "change-me-now";

    /** 标记文件默认路径：data 目录（与本地存储根 {@code ./data/storage} 同域），文件内容是 BCrypt 哈希。 */
    public static final String DEFAULT_RESET_MARKER_FILE = "./data/bootstrap-admin-reset.marker";

    /** 归一化外部配置：用户名去首尾空白，密码保持原样（空白本身也是需要识别的非法值），标记路径回落默认。 */
    public BootstrapAdminProperties {
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
        resetMarkerFile =
                resetMarkerFile == null || resetMarkerFile.isBlank()
                        ? DEFAULT_RESET_MARKER_FILE
                        : resetMarkerFile.trim();
    }
}
