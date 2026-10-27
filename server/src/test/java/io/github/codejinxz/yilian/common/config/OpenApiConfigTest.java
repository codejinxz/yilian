package io.github.codejinxz.yilian.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.junit.jupiter.api.Test;

/**
 * {@link OpenApiConfig} 的纯单元测试：不启动 Spring 上下文、不连数据库，只校验文档元数据契约。
 *
 * <p>这些值同时被 Swagger UI 与前端联调使用，改动需要显式同步测试，避免标题、版本或安全方案名被无意改掉。
 */
class OpenApiConfigTest {

    /** 被测配置直接实例化，避免为了三个字段拉起整个应用上下文。 */
    private final OpenAPI openAPI = new OpenApiConfig().yilianOpenAPI();

    /** 标题、版本与描述必须覆盖项目基本信息，版本需与 pom 保持一致。 */
    @Test
    void infoShouldCarryProjectMetadata() {
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("译练 API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(openAPI.getInfo().getDescription()).contains("ApiResponse", "HttpOnly");
    }

    /** me 接口的 {@code @SecurityRequirement} 按名字引用该方案，改名会让 Swagger UI 失去授权入口。 */
    @Test
    void shouldDeclareBearerJwtSecurityScheme() {
        SecurityScheme scheme = openAPI.getComponents().getSecuritySchemes().get("bearerAuth");
        assertThat(scheme).isNotNull();
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
    }

    /** 只声明本地 server，避免把生产地址写进代码或文档。 */
    @Test
    void shouldOnlyDeclareLocalServer() {
        assertThat(openAPI.getServers()).hasSize(1);
        assertThat(openAPI.getServers().get(0).getUrl()).isEqualTo("http://localhost:8080");
    }
}
