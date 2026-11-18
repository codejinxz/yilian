package io.github.codejinxz.yilian.auth.controller;

import io.github.codejinxz.yilian.auth.dto.UserSettingsRequest;
import io.github.codejinxz.yilian.auth.service.AuthService;
import io.github.codejinxz.yilian.auth.vo.UserSettingsResponse;
import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 当前用户的设置与账号数据接口，对应 API §4.9 / §4.11。
 *
 * <p>路径在 {@code /api/me} 下，由安全配置统一要求登录；Controller 只做参数校验与信封组装。
 */
@Tag(name = "用户设置与数据", description = "读取与更新当前用户的时区、每日目标、评分标准与 FSRS 期望保留率；导出本人服务端账号数据。")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AuthService authService;

    private final ObjectMapper objectMapper;

    /**
     * @param authService 认证与账号用例服务
     * @param objectMapper JSON 序列化（导出文件体）
     */
    public MeController(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
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

    /**
     * 导出本人服务端账号数据：账号块 + 练习域切片整包 JSON。
     *
     * <p>响应是<strong>文件下载语义</strong>：{@code Content-Disposition: attachment} + 固定文件名 {@code
     * yilian-export-<UTC日期>.json}，响应体为导出 JSON 本身而非统一 ApiResponse 信封——
     * 下载落盘的文件应当是可直接归档的纯数据，不携带信封噪声（本端点是信封约定的唯一显式例外， 失败路径仍由全局异常处理返回统一信封）。只含本人数据；不含密码哈希与 TOTP 材料。
     *
     * @param principal 当前用户
     * @return 导出 JSON 附件
     */
    @Operation(
            summary = "导出本人服务端账号数据",
            description =
                    "登录态本人数据整包 JSON（账号块 + attempts + attempt_gradings + appeals +"
                            + " review_cards + settings）；响应体为导出 JSON 本身（非统一信封），"
                            + "带 Content-Disposition: attachment，文件名 yilian-export-<UTC日期>.json。"
                            + "不含密码哈希与两步验证材料，不含他人数据。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "导出成功；响应体为导出 JSON 本身，Content-Disposition 附件"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未携带、已过期或非法的 access token（code=40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/export")
    public ResponseEntity<String> export(@AuthenticationPrincipal YilianUserPrincipal principal) {
        String body = objectMapper.writeValueAsString(authService.exportAccountData(principal));
        String filename = "yilian-export-" + LocalDate.now(ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
