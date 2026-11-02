package io.github.codejinxz.yilian.admin.service;

import io.github.codejinxz.yilian.admin.service.dto.AdminGenerationImportItem;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;

import java.util.List;

/**
 * 出题导入用例（API §8.11）：AI 生成句子经校验后整批入库。
 *
 * <p>入库复用 content 模块 {@code ContentImportService} 接缝（指纹去重、每题递增银行修订号）， 不创建 upload
 * 批次——批次视图是给前端的形状，可据此在「最近导入」展示， 但 upload 侧没有对应批次记录可回滚（删除走题目状态流转）。
 */
public interface AdminGenerationService {

    /**
     * 整批导入 AI 生成的句子；任一行校验失败或指纹重复都整批拒绝，不产生半批数据。
     *
     * <p>审计原因不经本方法（审计由控制器经 {@code OperationLogContext} 提供给切面）， 缺省值「AI 生成导入」在控制器补齐。
     *
     * @param actorId 操作管理员 id
     * @param bankId 目标题库 id（字符串数字）
     * @param items 待导入行，1–500
     * @return 批次视图（status=COMMITTED；upload 侧无批次记录，仅供展示）
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 行校验失败或 items
     *     为空（40000）、题库不可导入（40401）、指纹重复（40904）
     */
    ImportBatchViewResponse importGenerated(
            Long actorId, String bankId, List<AdminGenerationImportItem> items);
}
