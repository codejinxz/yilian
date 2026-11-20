package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.practice.service.vo.AdminAttemptRawView;

/**
 * 管理端查看答题原文用例（API §8.7）：原文默认不可见红线的唯一豁免口。
 *
 * <p>每次查看必须带原因并先落审计行，auditId 随视图返回；不提供任何批量形态。 判分取最新行（模型复核追加的高序号行），原文回放不做裁剪。
 */
public interface AdminAttemptRawService {

    /**
     * 查看一次答题原文；同事务内先落审计行再装配数据，落库失败整体回滚。
     *
     * @param attemptId 答题提交 UUID
     * @param reason 查看原因，必填；随审计行落库
     * @param actorId 操作管理员 id
     * @param actorName 操作管理员用户名
     * @param actorRole 操作管理员角色
     * @return 原文视图（answer + 最新 grading + auditId）
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 原因缺失（40000）或提交不存在（40403）
     */
    AdminAttemptRawView viewRaw(
            String attemptId, String reason, Long actorId, String actorName, String actorRole);
}
