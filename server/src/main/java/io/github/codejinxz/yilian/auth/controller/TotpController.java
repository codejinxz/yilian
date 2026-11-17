package io.github.codejinxz.yilian.auth.controller;

import io.github.codejinxz.yilian.auth.dto.TotpActivateRequest;
import io.github.codejinxz.yilian.auth.dto.TotpDisableRequest;
import io.github.codejinxz.yilian.auth.dto.TotpSetupRequest;
import io.github.codejinxz.yilian.auth.service.TotpAuthService;
import io.github.codejinxz.yilian.auth.vo.TotpActivationView;
import io.github.codejinxz.yilian.auth.vo.TotpSetupView;
import io.github.codejinxz.yilian.auth.vo.TotpStatusView;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 两步验证自助端点（/api/auth/2fa，API §4.10）：状态查询、注册两步（setup → activate）与 关闭。全部登录态（鉴权矩阵
 * anyRequest().authenticated()）， 机制对所有用户开放（公网硬前置面是管理员）。
 *
 * <p>限流口径与改密一致（账号 5 次/分钟）——都是凭据敏感写操作；密钥与恢复码明文只在 setup/activate 响应体里出现一次，审计切面不读请求体与响应体， 不泄密。
 */
@Tag(name = "两步验证", description = "TOTP 两步验证自助注册与关闭。密钥与恢复码明文仅随对应响应出现一次，" + "之后任何接口都拿不回。")
@RestController
@RequestMapping("/api/auth/2fa")
public class TotpController {

    private final TotpAuthService totpAuthService;

    /**
     * 只依赖 Service 接口，具体实现由 Spring 注入。
     *
     * @param totpAuthService 两步验证自助用例服务
     */
    public TotpController(TotpAuthService totpAuthService) {
        this.totpAuthService = totpAuthService;
    }

    /**
     * 查询当前用户两步验证状态。
     *
     * @param principal 当前用户
     * @return enabled / enrolledAt / 剩余恢复码数
     */
    @Operation(summary = "查询两步验证状态", description = "登录态读取；响应不含任何密钥材料。")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/status")
    public ApiResponse<TotpStatusView> status(
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(totpAuthService.status(principal));
    }

    /**
     * 注册第一步：密码确认后生成新密钥（密文落库、开关不动；重复调用作废旧密钥）。
     *
     * @param request 当前密码
     * @param principal 当前用户
     * @return Base32 密钥与 otpauth URI（仅本次展示）
     */
    @Operation(
            summary = "注册两步验证第一步",
            description =
                    "校验当前密码后生成新的 TOTP 密钥：密文落库但启用开关不变，"
                            + "Base32 密钥与 otpauth URI 文本仅随本响应展示一次；"
                            + "重复调用会作废旧密钥，需以最新密钥完成激活。")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "密钥已生成并落密文；data 为 TotpSetupView"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未登录（40100）或当前密码不正确（40101）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "参数缺失（40000）或密码超长（40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "触发限流（42900），带 Retry-After",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @RateLimit(
            scene = "totp-setup",
            dimensions = RateLimitDimension.ACCOUNT,
            limit = 5,
            windowSeconds = 60,
            accountExpression = "#principal.id()")
    @OperationLog(module = "account", action = "TOTP_SETUP")
    @PostMapping("/setup")
    public ApiResponse<TotpSetupView> setup(
            @Valid @RequestBody TotpSetupRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(totpAuthService.setup(principal, request));
    }

    /**
     * 注册第二步：验证器动态码通过后启用并签发一次性恢复码。
     *
     * @param request 验证器当前 8 位动态码
     * @param principal 当前用户
     * @return 10 个恢复码明文（仅本次展示）
     */
    @Operation(
            summary = "注册两步验证第二步（激活）",
            description =
                    "对 setup 落库的密钥验证当前动态码，通过后置启用开关并整体重发恢复码；" + "10 个恢复码明文仅随本响应展示一次，库内只存 BCrypt 哈希。")
    @SecurityRequirement(name = "bearerAuth")
    @RateLimit(
            scene = "totp-activate",
            dimensions = RateLimitDimension.ACCOUNT,
            limit = 5,
            windowSeconds = 60,
            accountExpression = "#principal.id()")
    @OperationLog(module = "account", action = "TOTP_ACTIVATE")
    @PostMapping("/activate")
    public ApiResponse<TotpActivationView> activate(
            @Valid @RequestBody TotpActivateRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(totpAuthService.activate(principal, request));
    }

    /**
     * 关闭两步验证：密码 + 当前动态码（或恢复码）双确认后清空注册与恢复码。
     *
     * @param request 密码与动态码
     * @param principal 当前用户
     * @return 成功提示
     */
    @Operation(
            summary = "关闭两步验证",
            description =
                    "密码与当前动态码双确认（动态码也接受剩余恢复码，验证器丢失时仍可自助关闭）；"
                            + "清空密钥、开关、启用时刻与全部恢复码，不吊销 refresh 会话"
                            + "（关闭 2FA 不降低会话安全性）。")
    @SecurityRequirement(name = "bearerAuth")
    @RateLimit(
            scene = "totp-disable",
            dimensions = RateLimitDimension.ACCOUNT,
            limit = 5,
            windowSeconds = 60,
            accountExpression = "#principal.id()")
    @OperationLog(module = "account", action = "TOTP_DISABLE")
    @PostMapping("/disable")
    public ApiResponse<Void> disable(
            @Valid @RequestBody TotpDisableRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        totpAuthService.disable(principal, request);
        return ApiResponse.successMessage("两步验证已关闭");
    }
}
