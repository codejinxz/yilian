package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 答疑回复视图（API §6.12）。
 *
 * @param answer 模型回答文本（引导思考，不含整句参考译文）
 * @param disclaimer 固定免责标注，常驻展示
 */
@Schema(description = "题目答疑回复：disclaimer 固定为「AI 生成，可能有误」，前端必须常驻展示")
public record TutorAskView(
        @Schema(description = "模型回答", requiredMode = Schema.RequiredMode.REQUIRED) String answer,
        @Schema(
                        description = "免责标注",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        example = "AI 生成，可能有误")
                String disclaimer) {}
