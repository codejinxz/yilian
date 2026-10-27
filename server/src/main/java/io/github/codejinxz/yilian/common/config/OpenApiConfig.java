package io.github.codejinxz.yilian.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 文档元数据。
 *
 * <p>只描述接口契约，不改变任何安全规则：{@code /v3/api-docs} 与 Swagger UI 仍只在 local profile 放行（见 {@code
 * SecurityConfig}），生产环境不暴露文档端点。
 */
@Configuration
public class OpenApiConfig {

    /** 与 {@code server/pom.xml} 的 {@code <version>} 保持一致；升级版本时同步修改，避免文档与构件版本漂移。 */
    private static final String API_VERSION = "0.1.0-SNAPSHOT";

    /** 文档中的安全方案名，控制器通过 {@code @SecurityRequirement(name = "bearerAuth")} 引用。 */
    private static final String BEARER_AUTH = "bearerAuth";

    /**
     * 构建 OpenAPI 文档：标题、版本、描述、Bearer JWT 安全方案与本地 server。
     *
     * @return OpenAPI 文档定义
     */
    @Bean
    public OpenAPI yilianOpenAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("译练 API")
                                .version(API_VERSION)
                                .description(
                                        "面向中文母语者的中译英刻意练习 PWA 后端接口。"
                                                + "所有接口统一返回 ApiResponse 信封：code=0 表示成功，"
                                                + "失败时 code 为业务错误码、HTTP 状态只表达类别；data 承载业务数据。"
                                                + "access token 通过 Authorization: Bearer 发送，"
                                                + "refresh token 只存在 HttpOnly Cookie 中，从不进入响应体。"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        BEARER_AUTH,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "短期 JWT access token，由登录或刷新接口签发；"
                                                                + "前端只存内存，"
                                                                + "请求时放在 Authorization: Bearer <token> 头中。")))
                .addServersItem(
                        new Server()
                                .url("http://localhost:8080")
                                .description("本地开发（local profile）"));
    }
}
