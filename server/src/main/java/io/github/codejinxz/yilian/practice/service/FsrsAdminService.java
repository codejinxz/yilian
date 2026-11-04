package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.practice.service.vo.FsrsParameterVersionView;

import java.util.List;

/**
 * FSRS 参数版本管理用例（管理端）：优化产出候选、版本列表、启用状态机。
 *
 * <p>权限在服务端由 {@code /api/admin/**} 的 ROLE_ADMIN 规则统一把关； 本接口只承载业务语义。
 */
public interface FsrsAdminService {

    /**
     * 触发优化：读目标用户的 review_logs 训练 SGD，产出 GLOBAL CANDIDATE 权重行。
     *
     * <p>「没有真实数据前不生成候选权重」：样本 &lt; {@code FsrsOptimizer.MIN_SAMPLES} 按 40000 拒绝， 不伪造已优化。
     *
     * @param targetUserId 训练数据所属用户（单人产品语境，显式指定）
     * @param actorId 触发优化的管理员 id（审计落 created_by）
     * @return 新候选版本视图
     */
    FsrsParameterVersionView optimize(Long targetUserId, Long actorId);

    /**
     * 全部参数版本（创建时间倒序），含权重与训练摘要。
     *
     * @return 版本视图列表
     */
    List<FsrsParameterVersionView> listParameters();

    /**
     * 启用候选版本：仅 CANDIDATE 可启用；同一事务内旧 GLOBAL ACTIVE → RETIRED、候选 → ACTIVE。
     *
     * @param versionId 候选版本 id
     * @param actorId 触启用的管理员 id（审计上下文）
     * @return 启用后的版本视图
     */
    FsrsParameterVersionView enable(Long versionId, Long actorId);
}
