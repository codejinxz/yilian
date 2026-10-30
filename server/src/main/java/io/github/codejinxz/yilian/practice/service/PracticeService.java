package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;

import io.github.codejinxz.yilian.practice.service.vo.AttemptView;

/**
 * 练习用例服务：作答提交（判分 + 幂等 + FSRS 排程）与作答详情。
 *
 * <p>跨模块取题目内容只经 {@code ContentQueryService}；用户设置经 {@code UserAccountService}。
 */
public interface PracticeService {

    /**
     * 提交一次作答：校验题目与版本、幂等判定、本地判分、写 attempt_gradings、
     * 按 SRS 四分支推进复习卡并写 review_logs。
     *
     * @param userId 当前用户 id
     * @param command 已校验的提交命令
     * @return 判分与排程视图；幂等重放时 replayed=true
     */
    AttemptView submitAttempt(Long userId, AttemptCommand command);

    /**
     * 查询作答详情（批改结果/刷新恢复）；只能看自己的。
     *
     * @param userId 当前用户 id
     * @param attemptId 提交 id
     * @return 与提交响应同构的视图
     */
    AttemptView getAttempt(Long userId, String attemptId);
}
