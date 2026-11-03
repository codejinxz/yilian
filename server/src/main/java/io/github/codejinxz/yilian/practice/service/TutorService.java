package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.practice.service.dto.LlmConfigUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmConfigView;
import io.github.codejinxz.yilian.practice.service.vo.TutorAskView;

/** 题目答疑用例服务（017）：被动一问一答，不判分、不进学习闭环。 */
public interface TutorService {

    /**
     * 就当前句发起一次答疑：三态门（未配置=40400 功能不存在；未开启=40304）、可见性校验（016 口径）、 上下文拼装（题目中文/考点/本人最近批注，不含参考译文）、LLM
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
     * 更新答疑开关（管理端）：写 {@code app_settings} 键 {@code llm.tutor}；baseUrl/model/apiKey 是 环境变量只读项，传非空值一律
     * 40000 拒绝（密钥不入库，AGENTS §3.4）。
     *
     * @param request 更新请求（enabled 必填）
     * @param operatorId 操作管理员 id，写审计字段
     * @return 更新后的配置视图
     */
    LlmConfigView updateAdminConfig(LlmConfigUpdateRequest request, Long operatorId);
}
