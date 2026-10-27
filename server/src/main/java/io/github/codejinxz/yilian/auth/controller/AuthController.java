package io.github.codejinxz.yilian.auth.controller;

import io.github.codejinxz.yilian.auth.dto.AuthResponse;
import io.github.codejinxz.yilian.auth.dto.ChangePasswordRequest;
import io.github.codejinxz.yilian.auth.dto.CsrfTokenResponse;
import io.github.codejinxz.yilian.auth.dto.CurrentUserResponse;
import io.github.codejinxz.yilian.auth.dto.LoginRequest;
import io.github.codejinxz.yilian.auth.dto.RegisterRequest;
import io.github.codejinxz.yilian.auth.service.AuthService;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口层：只做参数校验、调用 {@link AuthService} 并组装 {@link ApiResponse}。
 *
 * <p>接口文档按真实行为标注状态码：CSRF 失败固定 403，凭据或 refresh 会话失效固定 401， 参数与请求体问题固定 400，未预期异常固定
 * 500；不存在的状态不写进文档，避免前端按错误的状态分支。
 */
@Tag(
        name = "认证",
        description =
                "登录、刷新、登出与当前用户。access token 通过 Authorization: Bearer 发送；"
                        + "refresh token 只存在 HttpOnly Cookie 中，绝不进入响应体。")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 只依赖 Service 接口，具体实现由 Spring 注入。
     *
     * @param authService 认证用例服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 播种 CSRF cookie 并返回原始 token。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @return CSRF 令牌信息
     */
    @Operation(
            summary = "获取 CSRF 令牌",
            description =
                    "返回 CSRF token 的原始值，并把同一值写入浏览器可读 Cookie XSRF-TOKEN"
                            + "（Path=/、SameSite=Strict），完成 cookie 播种。"
                            + "前端在首次写请求（登录 / 刷新 / 登出）之前调用本接口，"
                            + "再把 token 回填到 X-XSRF-TOKEN 请求头。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "返回 CSRF token 原始信息并播种 XSRF-TOKEN cookie；data 为 CsrfTokenResponse"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常；返回统一 ApiResponse 信封，data 为 null",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/csrf")
    public ApiResponse<CsrfTokenResponse> csrf(
            HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(authService.issueCsrfToken(request, response));
    }

    /**
     * 登录并签发 access token，refresh 会话写入 HttpOnly Cookie。
     *
     * @param request 登录凭据
     * @param httpRequest 当前请求
     * @param httpResponse 当前响应
     * @return access token 与当前用户
     */
    @Operation(
            summary = "登录",
            description =
                    "校验用户名与密码，签发短期 JWT access token，并通过响应头 Set-Cookie 写入"
                            + " HttpOnly refresh 会话 Cookie（默认名 YILIAN_REFRESH、Path=/api/auth、"
                            + "SameSite=Strict）。必须携带由 GET /api/auth/csrf 取得的 X-XSRF-TOKEN；"
                            + "refresh token 不会出现在响应体中，凭据错误统一返回 401 以免枚举账号。",
            parameters = {
                @Parameter(
                        name = "X-XSRF-TOKEN",
                        in = ParameterIn.HEADER,
                        required = true,
                        description =
                                "CSRF 令牌，取自 GET /api/auth/csrf 返回的 token，"
                                        + "或浏览器可读 Cookie XSRF-TOKEN")
            })
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "登录成功；data 为 AuthResponse，refresh Cookie 在 Set-Cookie 响应头中"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "参数校验失败（用户名或密码为空、超长、密码超过 72 字节）或请求体不是合法 JSON；"
                                + "code 为 40000/40001，data.errors 可能带回字段级明细",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "用户名或密码错误、账号已停用（code=40101），或会话无法轮换（code=40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "CSRF 令牌缺失或不匹配（code=40301），前端应重新调用 GET /api/auth/csrf",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常；返回统一 ApiResponse 信封，data 为 null",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @RateLimit(
            scene = "login",
            dimensions = {RateLimitDimension.IP, RateLimitDimension.ACCOUNT},
            limit = 10,
            windowSeconds = 60,
            accountExpression = "#request.username()")
    @OperationLog(module = "account", action = "LOGIN")
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return ApiResponse.success(authService.login(request, httpRequest, httpResponse));
    }

    /**
     * 用 refresh cookie 换取新的 access token。
     *
     * @param httpRequest 当前请求
     * @param httpResponse 当前响应
     * @return 新的 access token 与当前用户
     */
    @Operation(
            summary = "刷新 access token",
            description =
                    "使用浏览器自动携带的 HttpOnly refresh Cookie 换取新的 access token，并轮换会话 id。"
                            + "本接口不接收请求体：不要提交 refresh token，也不要把 Cookie 写进 JSON。"
                            + "refresh 会话缺失、超过绝对有效期、用户已停用或删除时返回 401，"
                            + "前端应回到登录页。必须携带 X-XSRF-TOKEN。",
            parameters = {
                @Parameter(
                        name = "X-XSRF-TOKEN",
                        in = ParameterIn.HEADER,
                        required = true,
                        description =
                                "CSRF 令牌，取自 GET /api/auth/csrf 返回的 token，"
                                        + "或浏览器可读 Cookie XSRF-TOKEN"),
                @Parameter(
                        name = "YILIAN_REFRESH",
                        in = ParameterIn.COOKIE,
                        required = true,
                        description =
                                "HttpOnly refresh 会话 Cookie，由登录响应写入，浏览器自动携带，"
                                        + "脚本不可读取；名字可由 YILIAN_REFRESH_COOKIE_NAME 覆盖")
            })
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "刷新成功；data 为 AuthResponse，并轮换 refresh 会话 Cookie"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "refresh 会话缺失、已过期/被吊销、超出绝对有效期，或用户已停用（code=40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "CSRF 令牌缺失或不匹配（code=40301），前端应重新调用 GET /api/auth/csrf",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常；返回统一 ApiResponse 信封，data 为 null",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return ApiResponse.success(authService.refresh(httpRequest, httpResponse));
    }

    /**
     * 失效 refresh 会话并返回提示。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @return 成功提示
     */
    @Operation(
            summary = "登出",
            description =
                    "吊销当前 refresh 会话，并按用户名删除该用户全部 refresh 会话，随后清空安全上下文。"
                            + "Authorization: Bearer 为可选：不传也能登出并清理 Cookie；"
                            + "传入合法 access token 时同样按该用户吊销全部 refresh 会话。"
                            + "必须携带 X-XSRF-TOKEN。",
            parameters = {
                @Parameter(
                        name = "X-XSRF-TOKEN",
                        in = ParameterIn.HEADER,
                        required = true,
                        description =
                                "CSRF 令牌，取自 GET /api/auth/csrf 返回的 token，"
                                        + "或浏览器可读 Cookie XSRF-TOKEN")
            })
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "已退出登录；data 为 null，message 为「已退出登录」"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "CSRF 令牌缺失或不匹配（code=40301），前端应重新调用 GET /api/auth/csrf",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常；返回统一 ApiResponse 信封，data 为 null",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @OperationLog(module = "account", action = "LOGOUT")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
        return ApiResponse.successMessage("已退出登录");
    }

    /**
     * 邀请码注册并直接登录。
     *
     * @param request 注册请求
     * @param httpRequest 当前请求
     * @param httpResponse 当前响应
     * @return 新用户与 access token
     */
    @Operation(
            summary = "注册",
            description =
                    "使用邀请码创建账号并直接登录。邀请码是否必填由 yilian.registration.invite-required 决定；"
                            + "邮箱与用户名唯一，密码 8–72 字节。注册成功后同样写入 refresh 会话 Cookie。")
    @RateLimit(
            scene = "register",
            dimensions = {RateLimitDimension.IP},
            limit = 5,
            windowSeconds = 300)
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return ApiResponse.success(authService.register(request, httpRequest, httpResponse));
    }

    /**
     * 修改密码并吊销该用户全部 refresh 会话。
     *
     * @param request 改密请求
     * @param principal 当前用户
     * @param httpRequest 当前请求
     * @return 成功提示
     */
    @Operation(
            summary = "修改密码",
            description =
                    "校验当前密码后更新密码，并吊销该用户全部 refresh 会话；access token 最多再存活 15 分钟。"
                            + "首次登录 passwordChanged=false 时，本接口与 me/logout/csrf 是唯一可用的认证接口。")
    @SecurityRequirement(name = "bearerAuth")
    @OperationLog(module = "account", action = "PASSWORD_CHANGE")
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal,
            HttpServletRequest httpRequest) {
        authService.changePassword(principal, request, httpRequest);
        return ApiResponse.successMessage("密码已更新");
    }

    /**
     * 取当前登录用户。
     *
     * @param principal 当前 principal
     * @return 当前用户
     */
    @Operation(
            summary = "获取当前登录用户",
            description =
                    "解析 Authorization: Bearer access token，返回当前用户的 id、登录名与角色。"
                            + "响应不含密码哈希、账号状态等内部字段；前端可用 role 决定是否显示管理入口，"
                            + "真正的权限校验仍在服务端。")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "当前登录用户；data 为 CurrentUserResponse"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未携带、已过期或非法的 access token（code=40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "500",
                description = "未预期异常；返回统一 ApiResponse 信封，data 为 null",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(authService.currentUser(principal));
    }
}
