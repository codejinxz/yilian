package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.practice.service.vo.AdminAppealView;

/**
 * 管理端申诉终审用例（API §8.16）：分页检索 + 人工终审（LLM 初裁之后的必经人工关口）。
 *
 * <p>裁决口径（DATA-MODEL §5.10）：LLM 初裁只产「AI 意见」，status 保持 PENDING； 人工终审才落 ACCEPTED/REJECTED。ACCEPTED 的
 * APPEAL 经判分 overlay 参与后续判分， REJECTED 不回写任何题目数据。
 */
public interface AdminAppealService {

    /**
     * 申诉分页检索：created_at 倒序；status/kind 精确过滤。
     *
     * @param status 状态过滤（PENDING/ACCEPTED/REJECTED/WITHDRAWN），null 不过滤
     * @param kind 类型过滤（APPEAL/DISPUTE），null 不过滤
     * @param page 页码，从 1 起
     * @param size 每页大小（1–100，越界归 100）
     * @return 分页申诉行
     */
    PageResponse<AdminAppealView> list(String status, String kind, int page, int size);

    /**
     * 人工终审：仅 PENDING 可裁决（否则 40900）；落 status/终审理由/decided_by=ADMIN/decided_at， 初裁「AI 意见」（decision
     * JSON）保留不覆盖。
     *
     * @param appealId 申诉行 id
     * @param status 终审结论 ACCEPTED / REJECTED
     * @param reason 终审理由（必填，进审计）
     * @param operatorId 操作管理员 id（审计上下文由切面记录，参数供扩展校验）
     * @return 终审后的申诉行
     */
    AdminAppealView decide(long appealId, String status, String reason, Long operatorId);
}
