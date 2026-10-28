package io.github.codejinxz.yilian.upload.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * 导入批次状态机单测 [AC-3]：合法迁移字段落位、非法迁移一律拒绝。
 *
 * <p>preview→commit 契约下批次不允许绕过状态机改 {@code status}；这里把设计文档 §4.2 的迁移表逐条固化，防止后续实现回退到旧
 * upload→PROCESSING→resume 语义。 用例名以 {@code ac3_} 前缀对应计划 001 的 AC-3。
 */
class ImportBatchStateTransitionTest {

    private static final Instant T0 = Instant.parse("2026-10-29T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-29T00:01:00Z");
    private static final Instant T2 = Instant.parse("2026-10-29T00:02:00Z");
    private static final Instant T3 = Instant.parse("2026-10-29T00:03:00Z");
    private static final Instant T4 = Instant.parse("2026-10-29T00:04:00Z");

    /** 建一个处于 PREVIEWING 的批次，作为各用例起点。 */
    private static ImportBatch newPreviewingBatch() {
        return ImportBatch.previewing(
                "b-0001",
                1L,
                "JSON",
                "demo.json",
                "imports/2026/10/b-0001.json",
                "a".repeat(64),
                1024L,
                9L,
                "admin",
                T0);
    }

    /** [AC-3] 主链路 PREVIEWING→PREVIEWED→COMMITTING→COMMITTED→ROLLED_BACK，计数与时间戳逐段落位。 */
    @Test
    @DisplayName("[AC-3] 主链路：预览→提交→回滚的合法迁移字段落位")
    void ac3_legalChainPreviewToCommitToRollback() {
        ImportBatch batch = newPreviewingBatch();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.PREVIEWING);

        batch.markPreviewed(50, 47, 3, "{\"sampleRows\":[]}", T1);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.PREVIEWED);
        assertThat(batch.getTotalRows()).isEqualTo(50);
        assertThat(batch.getValidRows()).isEqualTo(47);
        assertThat(batch.getInvalidRows()).isEqualTo(3);
        assertThat(batch.getPreviewedAt()).isEqualTo(T1);

        batch.markCommitting(T2);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.COMMITTING);

        batch.markCommitted(47, T3);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.COMMITTED);
        assertThat(batch.getImportedRows()).isEqualTo(47);
        assertThat(batch.getCommittedAt()).isEqualTo(T3);
        assertThat(batch.isCommitted()).isTrue();

        batch.markRolledBack(9L, "误导入旧题库", T4);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.ROLLED_BACK);
        assertThat(batch.getRolledBackBy()).isEqualTo(9L);
        assertThat(batch.getRolledBackAt()).isEqualTo(T4);
        assertThat(batch.getRollbackReason()).isEqualTo("误导入旧题库");
    }

    /** [AC-3] FAILED 只能从非终态且未提交的状态进入。 */
    @Test
    @DisplayName("[AC-3] FAILED 仅限提交前进入，COMMITTED 拒绝")
    void ac3_failAllowedOnlyBeforeCommit() {
        ImportBatch previewing = newPreviewingBatch();
        previewing.markFailed("{\"reason\":\"解析失败\"}", T1);
        assertThat(previewing.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(previewing.getStatus().isTerminal()).isTrue();

        ImportBatch committed = newPreviewingBatch();
        committed.markPreviewed(1, 1, 0, null, T1);
        committed.markCommitting(T2);
        committed.markCommitted(1, T3);
        assertThatThrownBy(() -> committed.markFailed("{}", T4))
                .isInstanceOf(IllegalStateException.class);
    }

    /** [AC-3] 超期清扫针对未提交的批次（DATA-MODEL §5.6：超 24 小时未提交即 EXPIRED），COMMITTING 起拒绝。 */
    @Test
    @DisplayName("[AC-3] EXPIRED 仅限未提交状态进入")
    void ac3_expireOnlyBeforeCommitting() {
        ImportBatch previewing = newPreviewingBatch();
        previewing.markExpired(T1);
        assertThat(previewing.getStatus()).isEqualTo(ImportBatchStatus.EXPIRED);
        assertThat(previewing.getStatus().isTerminal()).isTrue();

        ImportBatch previewed = newPreviewingBatch();
        previewed.markPreviewed(1, 1, 0, null, T1);
        previewed.markExpired(T2);
        assertThat(previewed.getStatus()).isEqualTo(ImportBatchStatus.EXPIRED);

        ImportBatch committing = newPreviewingBatch();
        committing.markPreviewed(1, 1, 0, null, T1);
        committing.markCommitting(T2);
        assertThatThrownBy(() -> committing.markExpired(T3))
                .isInstanceOf(IllegalStateException.class);
        assertThat(committing.getStatus()).isEqualTo(ImportBatchStatus.COMMITTING);
    }

    /** [AC-3] 越过状态机的非法迁移全部拒绝，且原状态不被破坏。 */
    @Test
    @DisplayName("[AC-3] 非法迁移全部拒绝且原状态保持")
    void ac3_illegalTransitionsRejected() {
        ImportBatch previewing = newPreviewingBatch();
        assertThatThrownBy(() -> previewing.markCommitting(T1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> previewing.markCommitted(1, T1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> previewing.markRolledBack(9L, "原因", T1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(previewing.getStatus()).isEqualTo(ImportBatchStatus.PREVIEWING);

        ImportBatch previewed = newPreviewingBatch();
        previewed.markPreviewed(1, 1, 0, null, T1);
        assertThatThrownBy(() -> previewed.markPreviewed(2, 2, 0, null, T2))
                .isInstanceOf(IllegalStateException.class);
        // PREVIEWED → EXPIRED 现为合法迁移（超期未提交清扫），在 ac3_expireOnlyBeforeCommitting 单独覆盖。
        assertThat(previewed.getStatus()).isEqualTo(ImportBatchStatus.PREVIEWED);

        ImportBatch committed = newPreviewingBatch();
        committed.markPreviewed(1, 1, 0, null, T1);
        committed.markCommitting(T2);
        committed.markCommitted(1, T3);
        assertThatThrownBy(() -> committed.markCommitting(T4))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> committed.markPreviewed(2, 2, 0, null, T4))
                .isInstanceOf(IllegalStateException.class);
        assertThat(committed.getStatus()).isEqualTo(ImportBatchStatus.COMMITTED);

        ImportBatch rolledBack = newPreviewingBatch();
        rolledBack.markPreviewed(1, 1, 0, null, T1);
        rolledBack.markCommitting(T2);
        rolledBack.markCommitted(1, T3);
        rolledBack.markRolledBack(9L, "原因", T4);
        assertThatThrownBy(() -> rolledBack.markRolledBack(9L, "再来一次", T4))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rolledBack.markFailed("{}", T4))
                .isInstanceOf(IllegalStateException.class);
    }

    /** [AC-3] 回滚原因必填；行计数自洽（valid+invalid=total）由实体兜底。 */
    @Test
    @DisplayName("[AC-3] 回滚原因必填与行计数自洽校验")
    void ac3_guardClausesOnInputs() {
        ImportBatch batch = newPreviewingBatch();
        batch.markPreviewed(1, 1, 0, null, T1);
        batch.markCommitting(T2);
        batch.markCommitted(1, T3);
        assertThatThrownBy(() -> batch.markRolledBack(9L, " ", T4))
                .isInstanceOf(IllegalArgumentException.class);

        ImportBatch inconsistent = newPreviewingBatch();
        assertThatThrownBy(() -> inconsistent.markPreviewed(10, 6, 3, null, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [AC-3] 静态工厂入参校验：格式白名单、必填项、非负大小。 */
    @Test
    @DisplayName("[AC-3] 静态工厂入参校验")
    void ac3_factoryValidatesInputs() {
        assertThatThrownBy(
                        () ->
                                ImportBatch.previewing(
                                        "b-0002",
                                        1L,
                                        "XML",
                                        "demo.xml",
                                        "k",
                                        "a".repeat(64),
                                        1L,
                                        9L,
                                        "admin",
                                        T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ImportBatch.previewing(
                                        "b-0003",
                                        1L,
                                        "JSON",
                                        " ",
                                        "k",
                                        "a".repeat(64),
                                        1L,
                                        9L,
                                        "admin",
                                        T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ImportBatch.previewing(
                                        "b-0004",
                                        1L,
                                        "JSON",
                                        "demo.json",
                                        "k",
                                        "a".repeat(64),
                                        -1L,
                                        9L,
                                        "admin",
                                        T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ImportBatch.previewing(
                                        "b-0005",
                                        1L,
                                        "JSON",
                                        "demo.json",
                                        "k",
                                        "a".repeat(64),
                                        1L,
                                        9L,
                                        " ",
                                        T0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
