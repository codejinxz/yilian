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
 * 布尔与 keyLast4 尾号）。生效值优先级（028）：目录激活条目（目录管理见 {@link AdminLlmProviderController}）&gt; tutor 覆盖
 * （app_settings 键 llm.tutor 的 baseUrl/model + llm_provider_keys 预留 id=tutor 密钥）&gt; legacy 环境变量
 * （{@code YILIAN_LLM_*}）——legacy 形态全字段可编辑，目录激活态经 provider 抽屉编辑。
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
     * @return 配置视图（apiKey 只回 hasKey/keyLast4，附 legacy 形态来源 source）
     */
    @Operation(
            summary = "读取答疑 LLM 配置",
            description =
                    "返回 enabled、生效 base-url/model（目录激活 > tutor 覆盖 > env）、hasKey、"
                            + "keyLast4（tutor 密钥行尾号）与 source（custom|env）；API Key 明文不回读。")
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
     * 更新答疑 LLM 配置（028：legacy 形态全字段可编辑）。
     *
     * @param request 已校验的更新请求
     * @param principal 当前管理员
     * @return 更新后的配置视图
     */
    @Operation(
            summary = "更新答疑 LLM 配置",
            description =
                    "baseUrl/model 非空白=落 tutor 覆盖（即时生效），空/缺省=清除覆盖回 env；"
                            + "apiKey 写-only（非空白=加密入库到 llm_provider_keys 预留 id=tutor，空/缺省=不变更），"
                            + "clearKey=true 删 tutor 密钥行；enabled=true 且无可用客户端（凭证不齐/未激活）时 "
                            + "40000 拒绝且本次全部变更回滚。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "更新成功；data 为更新后的 LlmConfigView"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "enabled 缺失、baseUrl/model 形状或长度非法、apiKey 超长或凭证未配置无法开启（code=40000）",
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
