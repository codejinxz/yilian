package io.github.codejinxz.yilian.auth.controller;

import io.github.codejinxz.yilian.auth.dto.UserSettingsRequest;
import io.github.codejinxz.yilian.auth.dto.UserSettingsResponse;
import io.github.codejinxz.yilian.auth.service.AuthService;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户的设置接口，对应 API §4.9。
 *
 * <p>路径在 {@code /api/me} 下，由安全配置统一要求登录；Controller 只做参数校验与信封组装。
 */
@Tag(name = "用户设置", description = "读取与更新当前用户的时区、每日目标、评分标准与 FSRS 期望保留率。")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 读取当前用户设置。
     *
     * @param principal 当前用户
     * @return 设置视图
     */
    @Operation(summary = "读取用户设置", description = "返回时区、每日目标、评分标准、期望保留率、主题与通知开关。")
    @GetMapping("/settings")
    public ApiResponse<UserSettingsResponse> settings(
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(authService.getSettings(principal));
    }

    /**
     * 更新当前用户设置。
     *
     * @param request 待更新字段，至少一个非空
     * @param principal 当前用户
     * @return 更新后的设置
     */
    @Operation(summary = "更新用户设置", description = "所有字段可选，但至少提供一个；timezone 必须是 IANA 时区。")
    @OperationLog(module = "account", action = "SETTINGS_UPDATE")
    @PutMapping("/settings")
    public ApiResponse<UserSettingsResponse> updateSettings(
            @Valid @RequestBody UserSettingsRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(authService.updateSettings(principal, request));
    }
}
