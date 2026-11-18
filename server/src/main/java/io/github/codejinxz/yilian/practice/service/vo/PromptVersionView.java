package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Prompt 版本行视图（API §8.18）：管理端 Prompt 治理区块的列表/登记/激活回执。
 *
 * <p>content 全文回读给管理员（版本快照非密钥，DATA-MODEL §5.19）； 行内容（name/version/content）一经登记不改写，回执即库内快照。
 *
 * @param id 版本行 id
 * @param name 场景名：ask / review / appeal
 * @param version 版本号，同 name 内递增
 * @param label ACTIVE（当前取用）/ CANDIDATE（候选待启用）
 * @param content 系统提示全文
 * @param createdAt 登记时间（UTC）
 */
@Schema(description = "Prompt 版本行：append-only 快照，label 表达启用态")
public record PromptVersionView(
        @Schema(description = "版本行 id") Long id,
        @Schema(description = "场景名 ask/review/appeal") String name,
        @Schema(description = "版本号，同 name 内递增") int version,
        @Schema(description = "ACTIVE=当前取用 / CANDIDATE=候选") String label,
        @Schema(description = "系统提示全文（版本快照，非密钥）") String content,
        @Schema(description = "登记时间（UTC）") Instant createdAt) {}
