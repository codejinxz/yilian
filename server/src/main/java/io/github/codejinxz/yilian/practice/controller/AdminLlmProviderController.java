package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.LlmProviderAdminService;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderCreateRequest;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmProviderView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 provider 目录接口（API §8.15）：CC Switch 式多 provider 的元数据 CRUD 与激活切换，服务端 ADMIN 鉴权； 写操作全部审计 {@code
 * LLM_CONFIG_UPDATE}，读接口不标。apiKey 不入库不回读——每条目只回 hasKey（env 按 id 索引）；激活中的 provider 服务端拒绝删除。
 */
@Tag(name = "管理", description = "用户、题库、日志、出题与答疑 LLM 配置的管理端接口。")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/llm/providers")
public class AdminLlmProviderController {

    private final LlmProviderAdminService providerAdminService;

    /**
     * 只依赖 Service 接口，具体实现由 Spring 注入。
     *
     * @param providerAdminService provider 目录用例服务
     */
    public AdminLlmProviderController(LlmProviderAdminService providerAdminService) {
        this.providerAdminService = providerAdminService;
    }

    /**
     * 列出 provider 目录。
     *
     * @param principal 当前管理员
     * @return 目录条目列表（含 hasKey 与激活态）
     */
    @Operation(
            summary = "列出 LLM provider 目录",
            description = "每项含元数据、hasKey（YILIAN_LLM_KEY_<大写ID> 是否已配置）与激活态；apiKey 不回读。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "返回目录条目数组（可为空；空目录 + legacy 环境变量时答疑仍走 legacy 轨）"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未登录（40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "非管理员（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping
    public ApiResponse<List<LlmProviderView>> list(
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(providerAdminService.list());
    }

    /**
     * 新增 provider（追加目录末尾，默认未激活）。
     *
     * @param request 已校验的请求
     * @param principal 当前管理员
     * @return 新增后的条目视图
     */
    @Operation(
            summary = "新增 LLM provider",
            description =
                    "id 限小写字母/数字/连字符且目录内唯一；只收元数据，apiKey 走环境变量 "
                            + "YILIAN_LLM_KEY_<大写ID>。新增后需显式激活才成为答疑数据源。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "新增成功"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "字段缺失/形态非法/id 重复（code=40000）",
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
    @PostMapping
    public ApiResponse<LlmProviderView> create(
            @Valid @RequestBody LlmProviderCreateRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(providerAdminService.create(request, principal.id()));
    }

    /**
     * 更新 provider 元数据（id 走路径不可改）。
     *
     * @param id 目标 provider id
     * @param request 已校验的请求
     * @param principal 当前管理员
     * @return 更新后的条目视图
     */
    @Operation(summary = "更新 LLM provider", description = "id 走路径不可改；元数据整体覆盖（末写胜出）。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "更新成功"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "字段缺失/协议非法（code=40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未登录（40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "非管理员（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "provider 不存在（40400）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @OperationLog(module = "llm", action = "LLM_CONFIG_UPDATE")
    @PutMapping("/{id}")
    public ApiResponse<LlmProviderView> update(
            @PathVariable String id,
            @Valid @RequestBody LlmProviderUpdateRequest request,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(providerAdminService.update(id, request, principal.id()));
    }

    /**
     * 从目录移除 provider（激活中不可删）。
     *
     * @param id 目标 provider id
     * @param principal 当前管理员
     * @return 空数据成功信封
     */
    @Operation(
            summary = "删除 LLM provider",
            description = "只移除目录元数据（env 密钥不改动）；激活中的 provider 40000 拒绝，先激活其他条目。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "删除成功"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "激活中的 provider（code=40000）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未登录（40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "非管理员（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "provider 不存在（40400）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @OperationLog(module = "llm", action = "LLM_CONFIG_UPDATE")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable String id, @AuthenticationPrincipal YilianUserPrincipal principal) {
        providerAdminService.delete(id, principal.id());
        return ApiResponse.success(null);
    }

    /**
     * 激活 provider（激活唯一）；激活条目即答疑等 AI 功能的数据源，即时生效（客户端缓存按目录变更失效）。
     *
     * @param id 目标 provider id
     * @param principal 当前管理员
     * @return 激活后的条目视图
     */
    @Operation(
            summary = "激活 LLM provider",
            description =
                    "激活唯一；激活条目即时成为答疑等 AI 功能的数据源（017 答疑开关 llm.tutor 独立沿用）。"
                            + "凭证不齐（缺 env key/端点）时激活成功但答疑保持「功能不存在」。")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "激活成功（重复激活幂等）"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "未登录（40100）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "非管理员（40300）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "provider 不存在（40400）",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @OperationLog(module = "llm", action = "LLM_CONFIG_UPDATE")
    @PutMapping("/{id}/activate")
    public ApiResponse<LlmProviderView> activate(
            @PathVariable String id, @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(providerAdminService.activate(id, principal.id()));
    }
}
