package io.github.codejinxz.yilian.practice.service;

import io.github.codejinxz.yilian.practice.service.dto.LlmProviderCreateRequest;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmProviderView;

import java.util.List;

/**
 * provider 目录管理用例：CC Switch 式多 provider 元数据 CRUD 与激活切换（激活唯一）。密钥不入目录——按 provider id 走环境变量 {@code
 * YILIAN_LLM_KEY_<大写ID>}，所有写操作由控制器审计 {@code LLM_CONFIG_UPDATE}。
 */
public interface LlmProviderAdminService {

    /**
     * 列出全部 provider（保持目录顺序），每项含 hasKey（env 索引）与激活态。
     *
     * @return 目录条目列表
     */
    List<LlmProviderView> list();

    /**
     * 新增 provider（追加到目录末尾，默认未激活；答疑数据源切换需显式激活）。
     *
     * @param request 已校验的请求（id 形态与唯一性在此兜底校验）
     * @param operatorId 操作管理员 id（写审计字段）
     * @return 新增后的条目视图
     */
    LlmProviderView create(LlmProviderCreateRequest request, Long operatorId);

    /**
     * 更新 provider 元数据（id 走路径不可改）。
     *
     * @param id 目标 provider id
     * @param request 已校验的请求
     * @param operatorId 操作管理员 id
     * @return 更新后的条目视图
     */
    LlmProviderView update(String id, LlmProviderUpdateRequest request, Long operatorId);

    /**
     * 从目录移除 provider；激活中的 provider 不可删（先激活其他条目）——只移除目录元数据，env 密钥不改动， 激活态与答疑开关不受影响（删非激活条目）。
     *
     * @param id 目标 provider id
     * @param operatorId 操作管理员 id
     */
    void delete(String id, Long operatorId);

    /**
     * 激活 provider（激活唯一）：激活条目即答疑等 AI 功能的数据源（答疑开关 llm.tutor 沿用，二者独立）。
     *
     * @param id 目标 provider id
     * @param operatorId 操作管理员 id
     * @return 激活后的条目视图
     */
    LlmProviderView activate(String id, Long operatorId);
}
