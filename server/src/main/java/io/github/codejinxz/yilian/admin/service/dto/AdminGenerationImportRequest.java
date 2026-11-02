package io.github.codejinxz.yilian.admin.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 出题导入请求体（API §8.11）：AI 生成并经前端校验的句子整批入库。
 *
 * <p>字段级业务校验（长度/语块/挖空/拼接一致）在 {@code AdminGenerationServiceImpl} 完成—— 与 upload 导入校验器同口径（code point
 * 计数），Bean Validation 只挡缺请求体这类结构问题。
 *
 * @param bankId 目标题库 id（前端以字符串回传，数字与字符串都接受）
 * @param items 待导入句子，1–500 行
 * @param reason 审计原因，可空（缺省「AI 生成导入」）
 */
@Schema(description = "出题导入请求")
public record AdminGenerationImportRequest(
        @Schema(description = "目标题库 id") String bankId,
        @Schema(description = "待导入句子") List<AdminGenerationImportItem> items,
        @Schema(description = "审计原因，可空") String reason) {}
