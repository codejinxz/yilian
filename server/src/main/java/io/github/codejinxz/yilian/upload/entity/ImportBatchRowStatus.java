package io.github.codejinxz.yilian.upload.entity;

/**
 * 导入行状态，对应 {@code import_batch_rows.status}（DATA-MODEL §5.7）。
 *
 * <p>行级失败只标 INVALID、不改批次状态：与 PRD「50 句导入、3 行标红、47 句入库」语义一致； INVALID 行在 commit 时被跳过，仍是 INVALID。
 */
public enum ImportBatchRowStatus {

    /** 校验通过，等待 commit 入库。 */
    VALID,

    /** 校验失败，错误码在 error_codes；不参与 commit。 */
    INVALID,

    /** 已入库，回写了 sentence_id / sentence_version_id。 */
    IMPORTED,

    /** commit 时被跳过（如重复提交幂等）；保留原因在批次审计里。 */
    SKIPPED
}
