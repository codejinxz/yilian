package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.practice.dao.AppealAdminQueryDao;
import io.github.codejinxz.yilian.practice.dao.AppealDao;
import io.github.codejinxz.yilian.practice.entity.Appeal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

/**
 * {@link AdminAppealServiceImpl} 终审条件 UPDATE 单测 [AC-1]：029 并发原子门把「受影响行数=0」从 NOT_FOUND 改判为
 * 40900「该申诉已被终审」——预检通过后记录仍可能被并发终审（条件 UPDATE 的 WHERE status='PENDING' 失配）， 此时按冲突拒绝而不是 404，与并发集成测试的
 * HTTP 语义互为两翼（集成锁行为，单测锁文案与错误码映射）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAppealServiceImplTest {

    @Mock private AppealAdminQueryDao appealAdminQueryDao;

    @Mock private AppealDao appealDao;

    private AdminAppealServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminAppealServiceImpl(appealAdminQueryDao, appealDao);
    }

    /** [AC-1] 条件 UPDATE 行数=0（预检后被并发终审）：40900「该申诉已被终审」，不再误报 NOT_FOUND。 */
    @Test
    @DisplayName("[AC-1] 终审 UPDATE 行数=0 映射 40900 已被终审")
    void decideMapsMissedConditionalUpdateTo40900_ac1() {
        Appeal pending =
                Appeal.create(
                        "attempt-1",
                        "slot:0",
                        "01HSENTENCE0000000000000000",
                        0,
                        7L,
                        Appeal.AppealKind.APPEAL,
                        "She is paying...",
                        "主语可换",
                        java.time.Instant.now());
        when(appealDao.findById(60L)).thenReturn(Optional.of(pending));
        // 预检读到 PENDING，但 UPDATE 落库瞬间已被并发终审：WHERE status='PENDING' 失配，行数=0。
        when(appealDao.updateFinalDecision(
                        org.mockito.ArgumentMatchers.eq(60L),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.decide(60L, "ACCEPTED", "并发后到者", 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.IDEMPOTENT_CONFLICT);
                            assertThat(exception.getMessage()).isEqualTo("该申诉已被终审");
                        });
    }

    /** [AC-1] 条件 UPDATE 行数>0：终审照常落库并回读视图（原子门不影响正常路径）。 */
    @Test
    @DisplayName("[AC-1] 终审 UPDATE 成功路径不受影响")
    void decideSucceedsWhenConditionalUpdateMatches_ac1() {
        Appeal pending =
                Appeal.create(
                        "attempt-2",
                        "slot:1",
                        "01HSENTENCE0000000000000000",
                        1,
                        7L,
                        Appeal.AppealKind.APPEAL,
                        null,
                        "动词搭配不对",
                        java.time.Instant.now());
        when(appealDao.findById(61L)).thenReturn(Optional.of(pending));
        when(appealDao.updateFinalDecision(
                        org.mockito.ArgumentMatchers.eq(61L),
                        org.mockito.ArgumentMatchers.eq("REJECTED"),
                        org.mockito.ArgumentMatchers.eq("与参考语义相反"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        when(appealAdminQueryDao.findById(61L)).thenReturn(adminView());

        var view = service.decide(61L, "REJECTED", "与参考语义相反", 1L);
        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.decisionReason()).isEqualTo("与参考语义相反");
        assertThat(view.decidedBy()).isEqualTo("ADMIN");
    }

    /**
     * 构造终审后的列表视图样例（AdminAppealControllerTest 同款形状，与本次终审请求一致）。
     *
     * @return 视图
     */
    private static io.github.codejinxz.yilian.practice.service.vo.AdminAppealView adminView() {
        return new io.github.codejinxz.yilian.practice.service.vo.AdminAppealView(
                61L,
                "attempt-2",
                "slot:1",
                "01HSENTENCE0000000000000000",
                1,
                "APPEAL",
                "REJECTED",
                null,
                "动词搭配不对",
                "SUPPORT",
                "与参考语义相反",
                "ADMIN",
                java.time.Instant.parse("2026-11-06T03:00:00Z"),
                "stub:stub-model",
                3L,
                java.time.Instant.parse("2026-11-06T01:00:00Z"),
                "他越来越重视这件事情。",
                "it-appealer");
    }
}
