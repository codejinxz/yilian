package io.github.codejinxz.yilian.content.controller;

import io.github.codejinxz.yilian.common.api.ApiResponse;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.content.entity.BankVisibility;
import io.github.codejinxz.yilian.content.entity.ContentStatus;
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.dto.BankView;
import io.github.codejinxz.yilian.content.service.dto.SentencePage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题库只读接口（PUBLIC_OR_USER），对应 API §5.1–§5.3。
 *
 * <p>GET {@code /api/banks**} 由安全配置对匿名放行；「匿名只看公开、登录附私有」由 Service 依据 当前用户判定，Controller
 * 不做权限判断，只透传登录身份。 匿名时 {@code @AuthenticationPrincipal} 解析为 null（匿名 Authentication 的 principal 是字符串，
 * 与 {@link YilianUserPrincipal} 类型不匹配按 null 处理）。三个接口均为读操作，不标 {@code @OperationLog}。
 */
@Tag(name = "题库", description = "公开题库浏览与增量拉题；匿名可读公开题库，登录后附自己的私有题库。")
@RestController
@RequestMapping("/api/banks")
public class BankController {

    private final ContentQueryService contentQueryService;

    /**
     * 只依赖 Service 接口，不接触 Dao 与实体。
     *
     * @param contentQueryService 内容只读查询服务
     */
    public BankController(ContentQueryService contentQueryService) {
        this.contentQueryService = contentQueryService;
    }

    /**
     * 题库列表。
     *
     * @param page 页码，从 1 开始
     * @param size 每页大小，默认 20，上限 100
     * @param keyword 名称关键字，可空
     * @param visibility 可见性过滤，可空
     * @param status 状态过滤，公开端点仅 PUBLISHED 有意义，其他值返回空页
     * @param principal 当前用户；匿名时为 null
     * @return 分页题库视图
     */
    @Operation(summary = "题库列表", description = "匿名只返回公开题库，登录后附自己的私有题库；status 仅支持 PUBLISHED。")
    @GetMapping
    public ApiResponse<PageResponse<BankView>> listBanks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BankVisibility visibility,
            @RequestParam(required = false) ContentStatus status,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                contentQueryService.listBanks(
                        currentUserId(principal), keyword, visibility, status, page, size));
    }

    /**
     * 题库详情。
     *
     * @param bankId 题库 id
     * @param principal 当前用户；匿名时为 null
     * @return 题库视图；不存在或对当前用户不可见时返回 40401 信封
     */
    @Operation(summary = "题库详情", description = "匿名只能查看已发布的公开题库；私有题库对匿名按不存在处理，登录后非所有者返回 40300。")
    @GetMapping("/{bankId}")
    public ApiResponse<BankView> getBank(
            @PathVariable long bankId, @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                contentQueryService.getBank(bankId, currentUserId(principal), false));
    }

    /**
     * 按版本游标增量拉取题目。
     *
     * @param bankId 题库 id
     * @param sinceVersion 起始版本，首次拉取传 0；大于题库当前版本时返回 40005
     * @param limit 每页条数，默认 100，1–200
     * @param principal 当前用户；匿名时为 null
     * @return 版本化题目列表（含删除/停用 tombstone）
     */
    @Operation(
            summary = "增量拉取题目",
            description = "按 sinceVersion 游标拉取，首次传 0；删除/停用题目以 deleted=true 的 tombstone 返回。")
    @GetMapping("/{bankId}/sentences")
    public ApiResponse<SentencePage> listSentences(
            @PathVariable long bankId,
            @RequestParam(defaultValue = "0") long sinceVersion,
            @RequestParam(defaultValue = "100") int limit,
            @AuthenticationPrincipal YilianUserPrincipal principal) {
        return ApiResponse.success(
                contentQueryService.listSentences(
                        bankId, sinceVersion, limit, currentUserId(principal), false));
    }

    /**
     * 提取当前登录用户 id，未登录时为 null。
     *
     * @param principal 认证主体，匿名时为 null
     * @return 用户 id，未登录时为 null
     */
    private static Long currentUserId(YilianUserPrincipal principal) {
        return principal == null ? null : principal.id();
    }
}
