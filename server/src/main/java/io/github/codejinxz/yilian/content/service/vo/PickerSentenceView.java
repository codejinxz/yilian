package io.github.codejinxz.yilian.content.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 练习选题句列表行（API §5.3.1）：题库内按页浏览的可练句 + 当前用户的练过态。
 *
 * <p>与 §5.3 的增量同步视图刻意不同形：选题面板只需要展示与跳转的最小字段， 不携带语块/参考译文等完整内容（点选后由 §6.1 next?sentenceId= 取全量）。
 *
 * @param id 题目 ULID
 * @param zh 中文原句（当前快照；快照缺失行在 JOIN 中丢弃）
 * @param level 难度，可空
 * @param status 生命周期（PUBLISHED 可点选；其他状态仅作徽标展示）
 * @param reviewState 当前用户复习卡三档状态；无卡或匿名时为 null（前端展示「未学」）
 * @param reviewDueAt 当前用户卡片到期时间；无卡或匿名时为 null
 */
@Schema(description = "练习选题句列表行：练过态按当前用户投影，匿名恒为未学")
public record PickerSentenceView(
        @Schema(description = "题目 id") String id,
        @Schema(description = "中文原句") String zh,
        @Schema(description = "难度，可空") String level,
        @Schema(description = "生命周期") String status,
        @Schema(description = "当前用户卡状态，未练过为 null") String reviewState,
        @Schema(description = "当前用户卡到期时间，未练过为 null") Instant reviewDueAt) {}
