package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.practice.service.dto.LlmConfigUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmConfigView;
import io.github.codejinxz.yilian.practice.service.vo.LlmUsageView;
import io.github.codejinxz.yilian.practice.service.vo.TutorAskView;

/** 题目答疑用例服务：被动一问一答，不判分、不进学习闭环；管理端 LLM 配置与用量视图同域。 */
public interface TutorService {

    /**
     * 就当前句发起一次答疑：预算门（预算用尽=40306 强制门）、三态门（未配置=40400 功能不存在；未开启=40304）、 可见性校验（统一口径）、
     * 上下文拼装（题目中文/考点/本人最近批注，不含参考译文）、LLM 调用与降级。
     *
     * @param userId 当前用户 id
     * @param sentenceId 题目 id
     * @param question 用户问题（1-500 字符，已由控制器校验）
     * @return 回答与免责标注
     */
    TutorAskView ask(Long userId, String sentenceId, String question);

    /**
     * 读取答疑 LLM 配置（管理端）：apiKey 不回读，只回 hasKey；预算护栏字段（月度预算/定价）随键 llm.budget 投影。
     *
     * @return 配置视图
     */
    LlmConfigView getAdminConfig();

    /**
     * 更新答疑 LLM 配置（管理端；预算护栏扩月度预算与定价）：开关与 legacy 形态覆盖写 {@code app_settings} 键 {@code llm.tutor}，
     * 月度预算与定价写键 {@code llm.budget}（同事务），密钥加密 upsert 到 llm_provider_keys 预留 id=tutor（复用 provider
     * 密钥加密基建）。baseUrl/model 与 monthlyBudgetCny/pricing 均为状态型字段：非空=落值、 空/缺省=清除； apiKey
     * 写-only（非空白=加密入库，空/缺省=不变更）；clearKey=true 删 tutor 密钥行。 enabled=true 且无可用客户端时 40000 拒绝，本次全部变更回滚。
     *
     * @param request 更新请求（enabled 必填）
     * @param operatorId 操作管理员 id，写审计字段
     * @return 更新后的配置视图
     */
    LlmConfigView updateAdminConfig(LlmConfigUpdateRequest request, Long operatorId);

    /**
     * 读取本月用量视图（管理端，预算护栏）：UTC 自然月已用/预算/百分比/三态与分场景小计。
     *
     * @return 用量视图
     */
    LlmUsageView getAdminUsage();
}
