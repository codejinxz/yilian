package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.practice.service.dto.LlmConfigUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmConfigView;
import io.github.codejinxz.yilian.practice.service.vo.TutorAskView;

/** 题目答疑用例服务：被动一问一答，不判分、不进学习闭环。 */
public interface TutorService {

    /**
     * 就当前句发起一次答疑：三态门（未配置=40400 功能不存在；未开启=40304）、可见性校验（统一口径）、 上下文拼装（题目中文/考点/本人最近批注，不含参考译文）、LLM
     * 调用与降级。
     *
     * @param userId 当前用户 id
     * @param sentenceId 题目 id
     * @param question 用户问题（1-500 字符，已由控制器校验）
     * @return 回答与免责标注
     */
    TutorAskView ask(Long userId, String sentenceId, String question);

    /**
     * 读取答疑 LLM 配置（管理端）：apiKey 不回读，只回 hasKey。
     *
     * @return 配置视图
     */
    LlmConfigView getAdminConfig();

    /**
     * 更新答疑 LLM 配置（管理端，028）：开关与 legacy 形态覆盖写 {@code app_settings} 键 {@code llm.tutor}， 密钥加密 upsert 到
     * llm_provider_keys 预留 id=tutor（027B 基建）。baseUrl/model 非空白=落覆盖、 空/缺省=清除覆盖；apiKey
     * 写-only（非空白=加密入库，空/缺省=不变更）；clearKey=true 删 tutor 密钥行。 enabled=true 且无可用客户端时 40000 拒绝，本次全部变更回滚。
     *
     * @param request 更新请求（enabled 必填）
     * @param operatorId 操作管理员 id，写审计字段
     * @return 更新后的配置视图
     */
    LlmConfigView updateAdminConfig(LlmConfigUpdateRequest request, Long operatorId);
}
