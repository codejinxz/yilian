package io.github.codejinxz.yilian.practice.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.PromptAdminService;
import io.github.codejinxz.yilian.practice.service.dto.PromptRegisterRequest;
import io.github.codejinxz.yilian.practice.service.vo.PromptVersionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 Prompt 版本治理端点（/api/admin/prompts）：版本行检索、登记新版本与 激活换轨——prompt_versions 注册表写路径的应用内入口。
 *
 * <p>模块归属裁决：prompt_versions 实体/Dao 在 practice 域，治理端点沿用管理端申诉终审先例 （{@code AdminAppealController} 在
 * practice/controller）——controller 只依赖 practice 的 service 接口， 不让 admin 域摸 practice 的
 * dao/entity。管理员权限由 /api/admin/** 路径矩阵 （SecurityConfig hasRole("ADMIN")）服务端保证。无 DELETE
 * 端点（历史快照不删，append-only 语义）。
 */
@RestController
@RequestMapping("/api/admin/prompts")
@Tag(name = "admin/prompts", description = "管理端 Prompt 版本治理")
public class AdminPromptController {

    private final PromptAdminService promptAdminService;

    /**
     * @param promptAdminService Prompt 版本治理用例服务
     */
    public AdminPromptController(PromptAdminService promptAdminService) {
        this.promptAdminService = promptAdminService;
    }

    /**
     * 版本行分页检索：name/label 精确过滤，name 升序 + version 倒序。
     *
     * @param page 页码，从 1 起
     * @param size 每页大小，默认 20、上限 100（越界归 100）
     * @param name 场景名过滤（ask/review/appeal），可为空
     * @param label 标签过滤（ACTIVE/CANDIDATE），可为空
     * @return 分页版本行（含系统提示全文）
     */
    @Operation(
            summary = "Prompt 版本行分页检索",
            description = "name/label 精确过滤；行内容（name/version/content）为 append-only 快照，全文回读。")
    @GetMapping
    public ApiResponse<PageResponse<PromptVersionView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String label) {
        return ApiResponse.success(promptAdminService.list(name, label, page, size));
    }

    /**
     * 登记新版本：场景白名单 + 内容非空 ≤20000 字符；版本号=该场景现存最大 +1，label=CANDIDATE （不自动激活，上线必须显式激活）。
     *
     * @param request 场景名与系统提示全文
     * @param actor 当前管理员
     * @return 已登记的版本行（label=CANDIDATE）
     */
    @Operation(
            summary = "登记 Prompt 新版本",
            description = "append-only：行内容一经登记不改写；版本号服务端分配（现存最大 +1），登记后为 CANDIDATE 待激活。")
    @OperationLog(module = "admin", action = "PROMPT_REGISTER", targetType = "prompt-version")
    @PostMapping
    public ApiResponse<PromptVersionView> register(
            @Valid @RequestBody PromptRegisterRequest request,
            @AuthenticationPrincipal YilianUserPrincipal actor) {
        PromptVersionView view =
                promptAdminService.register(request.name(), request.content(), actor.id());
        // 注解属性只能是常量，动态版本行 id 写上下文由切面覆盖落库；prompt 全文不进审计（体积与敏感性双重考量）。
        OperationLogContext.set("prompt-version", String.valueOf(view.id()), null);
        return ApiResponse.success(view);
    }

    /**
     * 激活版本：仅 CANDIDATE 可激活；同事务「旧 ACTIVE 降 CANDIDATE → 新行置 ACTIVE → 注册表缓存 失效」，三条 LLM 链路下一次读取即换轨。对已
     * ACTIVE 行重复激活幂等返回。
     *
     * @param id 版本行 id
     * @param actor 当前管理员
     * @return 激活后的版本行（label=ACTIVE）
     */
    @Operation(
            summary = "激活 Prompt 版本",
            description = "仅 CANDIDATE 可激活（已 ACTIVE 幂等返回）；同事务完成降级/激活/缓存失效，即时换轨无需重启。")
    @OperationLog(module = "admin", action = "PROMPT_ACTIVATE", targetType = "prompt-version")
    @PostMapping("/{id}/activate")
    public ApiResponse<PromptVersionView> activate(
            @PathVariable long id, @AuthenticationPrincipal YilianUserPrincipal actor) {
        PromptVersionView view = promptAdminService.activate(id, actor.id());
        OperationLogContext.set("prompt-version", String.valueOf(view.id()), null);
        return ApiResponse.success(view);
    }
}
