package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.TutorService;
import io.github.codejinxz.yilian.practice.service.dto.LlmConfigUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmConfigView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
 * 管理端答疑 LLM 配置接口（API §8.14）：服务端 ADMIN 鉴权，写操作审计 {@code LLM_CONFIG_UPDATE}；API Key 永不回读（GET 回 hasKey
 * 布尔）。baseUrl/model/hasKey 反映激活 provider（目录管理见 {@link AdminLlmProviderController}），目录为空时回退 legacy
 * 环境变量。
 */
@Tag(name = "管理", description = "用户、题库、日志、出题与答疑 LLM 配置的管理端接口。")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/llm")
public class AdminLlmConfigController {

    private final TutorService tutorService;

    /**
     * 只依赖 Service 接口，具体实现由 Spring 注入。
     *
     * @param tutorService 答疑用例服务
     */
    public AdminLlmConfigController(TutorService tutorService) {
        this.tutorService = tutorService;
    }

    /**
     * 读取答疑 LLM 配置。
     *
     * @param principal 当前管理员
     * @return 配置视图（apiKey 只回 hasKey）
     */
    @Operation(
            summary = "读取答疑 LLM 配置",
            description = "返回 enabled、生效 base-url/model 与 hasKey；API Key 不回读。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "返回 LlmConfigView"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未登录（40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "非管理员（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/config")
    public ApiResponse<LlmConfigView> getConfig(
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(tutorService.getAdminConfig());
    }

    /**
     * 更新答疑开关。
     *
     * @param request 已校验的更新请求
     * @param principal 当前管理员
     * @return 更新后的配置视图
     */
    @Operation(
            summary = "更新答疑 LLM 配置",
            description =
                    "只受理 enabled；base-url/model/apiKey 不可在线修改（目录模式经 provider 目录与 "
                            + "YILIAN_LLM_KEY_<大写ID> 配置，legacy 经 YILIAN_LLM_* 环境变量），"
                            + "传非空值返回 40000。enabled=true 且无可用客户端（凭证不齐/未激活）时 40000 拒绝。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "更新成功；data 为更新后的 LlmConfigView"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "enabled 缺失、apiKey/baseUrl/model 传值或凭证未配置（code=40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未登录（40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "非管理员（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @OperationLog(module = "llm", action = "LLM_CONFIG_UPDATE")
    @PutMapping("/config")
    public ApiResponse<LlmConfigView> updateConfig(
            @Valid @RequestBody LlmConfigUpdateRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(tutorService.updateAdminConfig(request, principal.id()));
    }
}
