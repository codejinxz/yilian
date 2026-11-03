package io.github.codejinxz.yilian.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.admin.service.dto.AdminGenerationImportItem;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.ImportedSentence;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 出题导入服务单测 [AC-3]：行校验口径（zh/ref/note/chunks/blank/拼接一致/tags）、整批 fail-fast、 复用 content
 * 导入接缝（指纹去重由接缝负责，此处只验透传）、响应为 COMMITTED 批次视图。
 */
class AdminGenerationServiceImplTest {

    private static final Pattern BATCH_ID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private ContentImportService contentImportService;

    private AdminGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        contentImportService = mock(ContentImportService.class);
        service = new AdminGenerationServiceImpl(contentImportService);
    }

    /** [AC-3] 合法行经 content 导入接缝入库；externalKey 带批次前缀（016 P1·02#01）、响应为 COMMITTED 批次视图。 */
    @Test
    void importGeneratedSendsContentsThroughImportSeam_ac3() {
        when(contentImportService.importSentences(anyLong(), anyString(), anyList(), any(), any()))
                .thenReturn(List.of(new ImportedSentence("01JX", 11L, 1)));

        ImportBatchViewResponse response = service.importGenerated(1L, "5", List.of(validItem()));

        ArgumentCaptor<String> batchId = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SentenceContent>> contents =
                ArgumentCaptor.forClass((Class<List<SentenceContent>>) (Class<?>) List.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(contentImportService)
                .importSentences(
                        eq(5L), batchId.capture(), contents.capture(), eq(1L), now.capture());

        assertThat(batchId.getValue()).matches(BATCH_ID_PATTERN);
        assertThat(now.getValue()).isNotNull();
        SentenceContent content = contents.getValue().getFirst();
        // external_key = gen_<批次前 8 位十六进制>_<行号>：行号每请求重排，命名空间必须按批次
        // 隔离，否则同库第二次 AI 导入撞 uk_sentence_bank_external（016 P1·02#01）。
        assertThat(content.externalKey())
                .isEqualTo("gen_" + batchId.getValue().replace("-", "").substring(0, 8) + "_0001");
        assertThat(content.zh()).isEqualTo("他越来越胖。");
        assertThat(content.ref()).isEqualTo("He is getting fatter and fatter.");
        assertThat(content.chunks()).hasSize(2);
        assertThat(content.chunks().get(0).index()).isZero();
        assertThat(content.chunks().get(1).index()).isEqualTo(1);
        assertThat(content.blank()).containsExactly(1);
        assertThat(content.tags()).containsExactly("搭配");
        assertThat(content.level()).isEqualTo("CET-4");

        assertThat(response.batchId()).isEqualTo(batchId.getValue());
        assertThat(response.bankId()).isEqualTo(5L);
        assertThat(response.status()).isEqualTo("COMMITTED");
        assertThat(response.format()).isEqualTo("JSON");
        assertThat(response.originalFilename()).isEqualTo("AI 生成.json");
        assertThat(response.totalRows()).isEqualTo(1);
        assertThat(response.validRows()).isEqualTo(1);
        assertThat(response.invalidRows()).isZero();
        assertThat(response.importedRows()).isEqualTo(1);
        assertThat(response.errorSummary()).isNull();
    }

    /** [AC-3] 空 items 回 40000「没有可导入的句子」（与 mock 契约同文案）；不触达接缝。 */
    @Test
    void importGeneratedRejectsEmptyItemsWith40000_ac3() {
        assertThatThrownBy(() -> service.importGenerated(1L, "5", List.of()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED);
                            assertThat(exception.getMessage()).isEqualTo("没有可导入的句子");
                        });
        assertThatThrownBy(() -> service.importGenerated(1L, "5", null))
                .isInstanceOf(BusinessException.class);
        verify(contentImportService, never())
                .importSentences(anyLong(), anyString(), anyList(), any(), any());
    }

    /** [AC-3] 超过 500 行整批拒绝（单事务无界写防线）。 */
    @Test
    void importGeneratedRejectsOversizeBatch_ac3() {
        List<AdminGenerationImportItem> items =
                java.util.stream.IntStream.range(0, 501).mapToObj(i -> validItem()).toList();
        assertThatThrownBy(() -> service.importGenerated(1L, "5", items))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verify(contentImportService, never())
                .importSentences(anyLong(), anyString(), anyList(), any(), any());
    }

    /** [AC-3] 非数字 bankId 回 40000；指纹重复等接缝异常原样上抛（40904 整批回滚）。 */
    @Test
    void importGeneratedRejectsBadBankIdAndPropagatesSeamErrors_ac3() {
        assertThatThrownBy(() -> service.importGenerated(1L, "abc", List.of(validItem())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));

        when(contentImportService.importSentences(anyLong(), anyString(), anyList(), any(), any()))
                .thenThrow(new BusinessException(ApiCode.IMPORT_NOT_COMMITTABLE, "题库已存在相同题目"));
        assertThatThrownBy(() -> service.importGenerated(1L, "5", List.of(validItem())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.IMPORT_NOT_COMMITTABLE));
    }

    /** [AC-3] 行校验 fail-fast：zh 过短、blank 越界、拼接不一致、tags 缺失各回 40000 且带行号。 */
    @Test
    void importGeneratedValidatesRowsBeforeSeam_ac3() {
        // zh 4–60
        assertThatThrownBy(
                        () -> service.importGenerated(1L, "5", List.of(withZh(validItem(), "太短"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getMessage())
                                        .isEqualTo("第 1 行校验未通过：中文长度必须为 4–60 字"));
        // blank 下标不存在
        assertThatThrownBy(
                        () ->
                                service.importGenerated(
                                        1L, "5", List.of(withBlank(validItem(), List.of(9)))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getMessage())
                                        .isEqualTo("第 1 行校验未通过：blank 下标 9 不存在于 chunks"));
        // chunks[].answers[0] 拼接与 ref 不一致
        assertThatThrownBy(
                        () ->
                                service.importGenerated(
                                        1L,
                                        "5",
                                        List.of(
                                                withBlank(
                                                        withChunks(
                                                                validItem(),
                                                                List.of(
                                                                        new AdminGenerationImportItem
                                                                                .Chunk(
                                                                                null,
                                                                                "他",
                                                                                List.of("She"),
                                                                                null,
                                                                                null))),
                                                        List.of(0)))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getMessage())
                                        .isEqualTo("第 1 行校验未通过：chunks[].answers[0] 按序拼接与 ref 不一致"));
        // tags 缺失
        assertThatThrownBy(
                        () ->
                                service.importGenerated(
                                        1L,
                                        "5",
                                        List.of(
                                                new AdminGenerationImportItem(
                                                        "他越来越胖。",
                                                        "He is getting fatter and fatter.",
                                                        "主语 + 系表结构；现在进行时描述变化趋势。",
                                                        null,
                                                        List.of(),
                                                        validItem().chunks(),
                                                        validItem().blank()))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getMessage())
                                        .isEqualTo("第 1 行校验未通过：考点标签必须为 1–5 个"));
        // 第二行失败时行号正确（fail-fast 整批拒绝）
        assertThatThrownBy(
                        () ->
                                service.importGenerated(
                                        1L, "5", List.of(validItem(), withZh(validItem(), "太短"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getMessage())
                                        .isEqualTo("第 2 行校验未通过：中文长度必须为 4–60 字"));
        verify(contentImportService, never())
                .importSentences(anyLong(), anyString(), anyList(), any(), any());
    }

    /**
     * 合法行样例。
     *
     * @return 行
     */
    private static AdminGenerationImportItem validItem() {
        return new AdminGenerationImportItem(
                "他越来越胖。",
                "He is getting fatter and fatter.",
                "主语 + 系表结构；现在进行时描述变化趋势，注意 getting 的双写与比较级。",
                "CET-4",
                List.of("搭配"),
                List.of(
                        new AdminGenerationImportItem.Chunk(null, "他", List.of("He"), null, null),
                        new AdminGenerationImportItem.Chunk(
                                null, "越来越胖", List.of("is getting fatter and fatter"), null, null)),
                List.of(1));
    }

    /**
     * 派生：替换中文。
     *
     * @param source 原行
     * @param zh 新中文
     * @return 新行
     */
    private static AdminGenerationImportItem withZh(AdminGenerationImportItem source, String zh) {
        return new AdminGenerationImportItem(
                zh,
                source.ref(),
                source.note(),
                source.level(),
                source.tags(),
                source.chunks(),
                source.blank());
    }

    /**
     * 派生：替换挖空下标。
     *
     * @param source 原行
     * @param blank 新下标
     * @return 新行
     */
    private static AdminGenerationImportItem withBlank(
            AdminGenerationImportItem source, List<Integer> blank) {
        return new AdminGenerationImportItem(
                source.zh(),
                source.ref(),
                source.note(),
                source.level(),
                source.tags(),
                source.chunks(),
                blank);
    }

    /**
     * 派生：替换语块。
     *
     * @param source 原行
     * @param chunks 新语块
     * @return 新行
     */
    private static AdminGenerationImportItem withChunks(
            AdminGenerationImportItem source, List<AdminGenerationImportItem.Chunk> chunks) {
        return new AdminGenerationImportItem(
                source.zh(),
                source.ref(),
                source.note(),
                source.level(),
                source.tags(),
                chunks,
                source.blank());
    }
}
