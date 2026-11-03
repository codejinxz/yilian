package io.github.codejinxz.yilian.common.config;

import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * 部署形态判定：是否按 prod 加固口径运行（review-verify-2 03#06，AC-2）。
 *
 * <p>防的是<strong>prod 加固旁路</strong>：只认 profile 名 {@code prod} 时，别名部署 （容器里 {@code
 * spring.profiles.active=container/production/staging}） 会静默跳过默认口令 fail-fast，
 * 带着文档化默认密码直接上线。判定抽到本工具统一收敛：
 *
 * <ul>
 *   <li>任一激活 profile 名含 {@code prod}（prod/production/prod-eu 等别名一律命中）；
 *   <li>或环境变量 {@code YILIAN_PROD_MARKER=true}（显式标记，兜住完全陌生的 profile 命名）。
 * </ul>
 *
 * <p>偏保守设计：{@code nonprod} 这类含「prod」字样的非生产 profile 也会命中 —— 误伤的代价 只是要求显式口令（fail-closed）， 而漏放真 prod
 * 的代价是默认口令静默上线（fail-open），两害取其轻。 profile 全部小写化后比对，环境变量大小写不敏感（true/TRUE 均可）。
 */
public final class DeploymentMode {

    /** 显式 prod 标记的环境变量名；与 {@code YILIAN_*} 配置家族同风格。 */
    public static final String PROD_MARKER_PROPERTY = "YILIAN_PROD_MARKER";

    /** profile 名中判定 prod 的关键词；contains 语义见类注释的 fail-closed 裁决。 */
    private static final String PROD_PROFILE_KEYWORD = "prod";

    private DeploymentMode() {}

    /**
     * 判定当前是否按 prod 加固口径运行。
     *
     * @param environment Spring 环境，不能为 null
     * @return 任一激活 profile 含 {@code prod} 或 {@code YILIAN_PROD_MARKER=true} 时返回 true
     */
    public static boolean isProd(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (profile.toLowerCase(Locale.ROOT).contains(PROD_PROFILE_KEYWORD)) {
                return true;
            }
        }
        return "true".equalsIgnoreCase(environment.getProperty(PROD_MARKER_PROPERTY));
    }
}
