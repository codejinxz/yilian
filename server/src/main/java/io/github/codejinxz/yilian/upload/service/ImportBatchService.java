package io.github.codejinxz.yilian.upload.service;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.upload.dto.ImportBatchDetailResponse;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;
import io.github.codejinxz.yilian.upload.dto.ImportPreviewResponse;

import org.springframework.web.multipart.MultipartFile;

/** 导入批次服务（preview→commit→rollback 编排，切片设计 §4.1 五端点）。 */
public interface ImportBatchService {

    /**
     * 同步预览：校验文件 → 存对象存储 → 解析 → 逐行业务校验 + 指纹去重 → 单事务落批次（PREVIEWED）与行级结果；不写题目。
     *
     * @param file 上传文件
     * @param bankId 目标题库 id
     * @param format 声明格式 {@code JSON} / {@code CSV}
     * @param actor 当前管理员
     * @return 预览结果（行级明细含错误）
     */
    ImportPreviewResponse preview(
            MultipartFile file, Long bankId, String format, YilianUserPrincipal actor);

    /**
     * 提交批次：单事务把 VALID 行写为题目（bank.version 逐题递增）；重复提交幂等返回。
     *
     * @param batchId 批次 id
     * @param actor 当前管理员
     * @return 提交后的批次视图
     */
    ImportBatchViewResponse commit(String batchId, YilianUserPrincipal actor);

    /**
     * 回滚已提交批次：题目 RECYCLED 软删 + 银行修订号逐题递增（增量流下发 tombstone）； 已回滚批次幂等返回。
     *
     * @param batchId 批次 id
     * @param reason 回滚原因（审计必填）
     * @param actor 当前管理员
     * @return 回滚后的批次视图
     */
    ImportBatchViewResponse rollback(String batchId, String reason, YilianUserPrincipal actor);

    /**
     * 批次列表：题库/状态/时间范围动态筛选，创建时间倒序。
     *
     * @param page 页码（1 基）
     * @param size 每页条数（1–100）
     * @param bankId 题库 id，null 不过滤
     * @param status 批次状态，null 不过滤
     * @param from 创建日期下界（含，UTC），null 不过滤
     * @param to 创建日期上界（含，UTC），null 不过滤
     * @return 分页批次
     */
    PageResponse<ImportBatchViewResponse> list(
            int page, int size, Long bankId, String status, String from, String to);

    /**
     * 批次详情 + 行分页。
     *
     * @param batchId 批次 id
     * @param page 页码
     * @param size 每页条数
     * @param rowStatus 行状态过滤，null 不过滤
     * @return 批次与行分页
     */
    ImportBatchDetailResponse detail(String batchId, int page, int size, String rowStatus);

    /**
     * 对账恢复：把残留 COMMITTING 状态的批次收敛到终态，避免「重试 40904、回滚又要 COMMITTED」 的死锁状态（015 缺陷 #3 的恢复面）。
     *
     * <p>判定按行数据：存在已关联题目的行 → 内容确已入库，补 markCommitted； 否则原子事务未完成、什么都没入库，标记 FAILED（终态，管理员重新导入新批次）。
     *
     * @return 本次收敛的批次数
     */
    int reconcileCommittingBatches();
}
