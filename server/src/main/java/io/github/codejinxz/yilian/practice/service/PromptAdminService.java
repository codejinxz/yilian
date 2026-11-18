package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.practice.service.vo.PromptVersionView;

/**
 * 管理端 Prompt 版本治理用例（API §8.18）：检索 + 登记 + 激活，补齐 prompt_versions 注册表的写半边。
 *
 * <p>治理口径（DATA-MODEL §5.19）：行内容（name/version/content）一经登记不改写—— append-only；新版本一律追加更高
 * version（登记即分配「现存最大 +1」，label=CANDIDATE）；启用态由 label 流转表达，激活把旧 ACTIVE 降级为
 * CANDIDATE（历史版本可再激活，重启旧版本也是合法治理动作）； 激活后 {@code PromptRegistry.evictAll()} 即时换轨（缓存不回旧值）。无 DELETE
 * 端点（历史快照不删）。
 */
public interface PromptAdminService {

    /**
     * 版本行分页检索：name/label 精确过滤，name 升序 + version 倒序（同场景新版本在前）。
     *
     * @param name 场景名过滤（ask/review/appeal），null 不过滤；非法值 40000
     * @param label 标签过滤（ACTIVE/CANDIDATE），null 不过滤；非法值 40000
     * @param page 页码，从 1 起
     * @param size 每页大小（1–100，越界归 100，统一分页口径）
     * @return 分页版本行
     */
    PageResponse<PromptVersionView> list(String name, String label, int page, int size);

    /**
     * 登记新版本：name 白名单校验、content 非空且 ≤20000 字符（码点口径）；版本号=该 name 现存 最大 +1，label=CANDIDATE（不自动激活——
     * 上线动作必须显式走激活，治理可见）。
     *
     * @param name 场景名（ask/review/appeal）
     * @param content 系统提示全文（版本快照，落库后不改写）
     * @param operatorId 操作管理员 id（审计上下文由切面记录）
     * @return 已登记的版本行（label=CANDIDATE）
     */
    PromptVersionView register(String name, String content, Long operatorId);

    /**
     * 激活版本：仅 CANDIDATE 可激活；同事务「旧 ACTIVE 降 CANDIDATE → 新行置 ACTIVE → 注册表缓存 失效」，三条 LLM 链路下一次读取即取新版本。对已
     * ACTIVE 行重复激活幂等返回（不重复降级）。
     *
     * @param id 版本行 id
     * @param operatorId 操作管理员 id（审计上下文由切面记录）
     * @return 激活后的版本行（label=ACTIVE）
     */
    PromptVersionView activate(long id, Long operatorId);
}
