package io.github.codejinxz.yilian.auth.dto;

import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前登录用户的展示视图。
 *
 * <p>只暴露 id、登录名、角色与是否已改初始密码，不含密码哈希、账号状态等内部字段；前端据此决定是否显示管理入口与强制改密页， 真正的权限校验仍在服务端。
 *
 * @param id 用户 id，字符串化避免 JS 精度丢失
 * @param username 登录名
 * @param role 角色名，如 {@code ADMIN}
 * @param passwordChanged 是否已修改初始密码
 */
@Schema(description = "当前登录用户视图：id 序列化为字符串；passwordChanged=false 时前端应跳转改密。")
public record CurrentUserResponse(
        @Schema(
                        description = "用户 id（字符串，避免 JS 精度丢失）",
                        example = "1",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String id,
        @Schema(
                        description = "登录名",
                        example = "admin",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String username,
        @Schema(
                        description = "角色名：ADMIN 或 USER；服务端会补上 ROLE_ 前缀作为权限",
                        example = "ADMIN",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String role,
        @Schema(
                        description = "是否已修改初始密码；false 时除 me/password/logout/csrf 外业务接口返回 40303",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                boolean passwordChanged) {

    /**
     * 从安全上下文的 principal 转换，避免把 principal 直接序列化给前端。
     *
     * @param principal 当前请求的认证主体
     * @return 用户展示视图
     */
    public static CurrentUserResponse from(YilianUserPrincipal principal) {
        return new CurrentUserResponse(
                Long.toString(principal.id()),
                principal.getUsername(),
                principal.role(),
                principal.passwordChanged());
    }
}
