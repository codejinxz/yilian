package io.github.codejinxz.yilian.practice.service.vo;

import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 取下一题响应（API §6.1）：优先到期复习，其次新题；无可练题时整个 data 为 null。
 *
 * @param sentence 题目内容
 * @param review 复习卡投影；新题时为 null
 * @param selection 选题来源 DUE/NEW
 */
@Schema(description = "下一题：selection=DUE 表示到期复习，NEW 表示新题")
public record NextPracticeView(
        @Schema(description = "题目内容") SentenceDetail sentence,
        @Schema(description = "复习卡，新题时为 null") ReviewCardView review,
        @Schema(description = "选题来源") String selection) {}
