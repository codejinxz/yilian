package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.admin.service.AdminAuditEventService;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.practice.dao.AttemptDao;
import io.github.codejinxz.yilian.practice.dao.AttemptGradingDao;
import io.github.codejinxz.yilian.practice.entity.Attempt;
import io.github.codejinxz.yilian.practice.entity.AttemptGrading;
import io.github.codejinxz.yilian.practice.service.vo.AdminAttemptRawView;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/** 查看答题原文服务单测（API §8.7）：原因必填、提交存在性、审计先行落库拿 auditId、 原文 JSON 与最新判分的装配、重复查看各落一条审计。 */
class AdminAttemptRawServiceImplTest {

    private static final String ATTEMPT_ID = "3f0e5c1a-0000-0000-0000-000000000001";

    private AttemptDao attemptDao;

    private AttemptGradingDao attemptGradingDao;

    private AdminAuditEventService adminAuditEventService;

    private AdminAttemptRawServiceImpl service;

    @BeforeEach
    void setUp() {
        attemptDao = mock(AttemptDao.class);
        attemptGradingDao = mock(AttemptGradingDao.class);
        adminAuditEventService = mock(AdminAuditEventService.class);
        service =
                new AdminAttemptRawServiceImpl(
                        attemptDao,
                        attemptGradingDao,
                        adminAuditEventService,
                        JsonMapper.builder().build());
    }

    /** 原因缺失或空白：40000，不读提交也不落审计。 */
    @Test
    void viewRawRejectsBlankReasonWith40000() {
        assertThatThrownBy(() -> service.viewRaw(ATTEMPT_ID, "   ", 1L, "admin", "ADMIN"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.viewRaw(ATTEMPT_ID, null, 1L, "admin", "ADMIN"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(attemptDao, attemptGradingDao, adminAuditEventService);
    }

    /** 提交不存在：40403；没有发生查看，不落审计行。 */
    @Test
    void viewRawRejectsMissingAttemptWith40403() {
        when(attemptDao.findById(ATTEMPT_ID)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.viewRaw(ATTEMPT_ID, "评分申诉", 1L, "admin", "ADMIN"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.ATTEMPT_NOT_FOUND));

        verify(attemptDao).findById(ATTEMPT_ID);
        verifyNoInteractions(attemptGradingDao, adminAuditEventService);
    }

    /** 成功查看：审计行按固定动作/对象类型落库拿回 auditId，视图回放原文 JSON 与最新判分。 */
    @Test
    void viewRawAuditsThenAssemblesAnswerWithLatestGrading() {
        AttemptGrading latest = grading(100L, 2, 80);
        when(attemptDao.findById(ATTEMPT_ID)).thenReturn(java.util.Optional.of(attempt()));
        when(attemptGradingDao.findLatestByAttemptId(ATTEMPT_ID))
                .thenReturn(java.util.Optional.of(latest));
        when(adminAuditEventService.recordViewAttemptRaw(
                        eq(1L), eq("admin"), eq("ADMIN"), eq(ATTEMPT_ID), eq("评分申诉")))
                .thenReturn(101L);
        AdminAttemptRawView view = service.viewRaw(ATTEMPT_ID, " 评分申诉 ", 1L, "admin", "ADMIN");

        assertThat(view.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(view.userId()).isEqualTo("42");
        assertThat(view.sentenceId()).isEqualTo("01JAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(view.answer().get("mode").asText()).isEqualTo("CLOZE");
        assertThat(view.grading()).isNotNull();
        assertThat(view.grading().id()).isEqualTo("100");
        assertThat(view.grading().score()).isEqualTo(80);
        assertThat(view.grading().result().get("sequence").asInt()).isEqualTo(2);
        assertThat(view.auditId()).isEqualTo("101");
        verify(adminAuditEventService)
                .recordViewAttemptRaw(eq(1L), eq("admin"), eq("ADMIN"), eq(ATTEMPT_ID), eq("评分申诉"));
    }

    /** 尚无判分的提交：grading 为 null，审计行照常落地。 */
    @Test
    void viewRawToleratesAttemptWithoutGrading() {
        when(attemptDao.findById(ATTEMPT_ID)).thenReturn(java.util.Optional.of(attempt()));
        when(attemptGradingDao.findLatestByAttemptId(ATTEMPT_ID))
                .thenReturn(java.util.Optional.empty());
        when(adminAuditEventService.recordViewAttemptRaw(any(), any(), any(), any(), any()))
                .thenReturn(102L);

        AdminAttemptRawView view = service.viewRaw(ATTEMPT_ID, "评分申诉", 1L, "admin", "ADMIN");

        assertThat(view.grading()).isNull();
        assertThat(view.auditId()).isEqualTo("102");
    }

    /** 重复查看：每次各落一条审计（append-only，不合并），auditId 各自返回。 */
    @Test
    void repeatedViewsEachLandTheirOwnAuditRow() {
        when(attemptDao.findById(ATTEMPT_ID)).thenReturn(java.util.Optional.of(attempt()));
        when(attemptGradingDao.findLatestByAttemptId(ATTEMPT_ID))
                .thenReturn(java.util.Optional.empty());
        when(adminAuditEventService.recordViewAttemptRaw(any(), any(), any(), any(), any()))
                .thenReturn(201L, 202L);
        AdminAttemptRawView first = service.viewRaw(ATTEMPT_ID, "评分申诉", 1L, "admin", "ADMIN");
        AdminAttemptRawView second = service.viewRaw(ATTEMPT_ID, "用户投诉", 1L, "admin", "ADMIN");

        assertThat(first.auditId()).isEqualTo("201");
        assertThat(second.auditId()).isEqualTo("202");
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(adminAuditEventService, times(2))
                .recordViewAttemptRaw(
                        eq(1L), eq("admin"), eq("ADMIN"), eq(ATTEMPT_ID), reasonCaptor.capture());
        assertThat(reasonCaptor.getAllValues()).containsExactly("评分申诉", "用户投诉");
    }

    /** answer 文档损坏（理论上不可能）：按未知异常收敛为 50000，不回放半截文档。 */
    @Test
    void viewRawFailsWith50000OnCorruptedAnswerDocument() {
        when(attemptDao.findById(ATTEMPT_ID)).thenReturn(java.util.Optional.of(corruptedAttempt()));
        when(attemptGradingDao.findLatestByAttemptId(ATTEMPT_ID))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.viewRaw(ATTEMPT_ID, "评分申诉", 1L, "admin", "ADMIN"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.INTERNAL_ERROR));
        // 审计在装配前已落（生产环境随事务回滚），单元层只验证调用时序。
    }

    /** 构造一条已提交的答题记录（answer 为服务端写路径产出的合法 JSON）。 */
    private static Attempt attempt() {
        return Attempt.create(
                ATTEMPT_ID,
                42L,
                null,
                "01JAAAAAAAAAAAAAAAAAAAAAAAAA",
                1L,
                1,
                Attempt.AttemptMode.CLOZE,
                Attempt.AttemptKind.FIRST,
                null,
                "{\"schemaVersion\":1,\"mode\":\"CLOZE\",\"slots\":[]}",
                "answer-hash",
                (short) 0,
                null,
                null,
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    /** 构造 answer 文档损坏的提交（防御路径专用）。 */
    private static Attempt corruptedAttempt() {
        return Attempt.create(
                ATTEMPT_ID,
                42L,
                null,
                "01JAAAAAAAAAAAAAAAAAAAAAAAAA",
                1L,
                1,
                Attempt.AttemptMode.CLOZE,
                Attempt.AttemptKind.FIRST,
                null,
                "{not-json",
                "answer-hash",
                (short) 0,
                null,
                null,
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    /** 判分行 id 由数据库生成，用 mock 控制主键与字段。 */
    private static AttemptGrading grading(long id, int sequence, Integer score) {
        AttemptGrading grading = mock(AttemptGrading.class);
        when(grading.getId()).thenReturn(id);
        when(grading.getSequence()).thenReturn(sequence);
        when(grading.getScore()).thenReturn(score);
        when(grading.getResult()).thenReturn("{\"sequence\":" + sequence + "}");
        return grading;
    }
}
