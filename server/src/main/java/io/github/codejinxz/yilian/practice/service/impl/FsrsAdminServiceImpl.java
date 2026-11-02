package io.github.codejinxz.yilian.practice.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.practice.dao.FsrsParameterVersionDao;
import io.github.codejinxz.yilian.practice.dao.ReviewLogDao;
import io.github.codejinxz.yilian.practice.entity.FsrsParameterVersion;
import io.github.codejinxz.yilian.practice.entity.ReviewLog;
import io.github.codejinxz.yilian.practice.optimizer.FsrsOptimizer;
import io.github.codejinxz.yilian.practice.service.FsrsAdminService;
import io.github.codejinxz.yilian.practice.service.vo.FsrsParameterVersionView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FSRS 参数管理实现：优化器调用、候选落库与启用状态机。
 *
 * <p>启用是唯一改写 fsrs_parameter_versions.status 的业务路径（CANDIDATE→ACTIVE、旧 ACTIVE→RETIRED），
 * weights/metrics 列只增不改；审计上下文在这里写入动态 targetId，切面落库（管理员动作可追溯）。
 */
@Service
public class FsrsAdminServiceImpl implements FsrsAdminService {

    private static final Logger log = LoggerFactory.getLogger(FsrsAdminServiceImpl.class);

    private final FsrsParameterVersionDao fsrsParameterVersionDao;

    private final ReviewLogDao reviewLogDao;

    private final ObjectMapper objectMapper;

    /**
     * @param fsrsParameterVersionDao 参数版本 Dao
     * @param reviewLogDao 排程日志 Dao（训练集）
     * @param objectMapper JSON 序列化（weights/metrics 落库与视图解码）
     */
    public FsrsAdminServiceImpl(
            FsrsParameterVersionDao fsrsParameterVersionDao,
            ReviewLogDao reviewLogDao,
            ObjectMapper objectMapper) {
        this.fsrsParameterVersionDao = fsrsParameterVersionDao;
        this.reviewLogDao = reviewLogDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public FsrsParameterVersionView optimize(Long targetUserId, Long actorId) {
        List<ReviewLog> logs = reviewLogDao.findByUserId(targetUserId);
        // 「没有真实数据前不生成候选权重」：样本不足直接拒绝，不写占位行、不伪造已优化。
        if (logs.size() < FsrsOptimizer.MIN_SAMPLES) {
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED,
                    "复习样本不足 "
                            + FsrsOptimizer.MIN_SAMPLES
                            + " 条（当前 "
                            + logs.size()
                            + " 条），暂不能生成候选权重");
        }
        List<FsrsOptimizer.Sample> samples =
                logs.stream()
                        .map(
                                logRow ->
                                        new FsrsOptimizer.Sample(
                                                logRow.getId(),
                                                logRow.getReviewAt(),
                                                logRow.getRating()))
                        .toList();
        FsrsOptimizer.Result result = FsrsOptimizer.optimize(samples);
        Instant now = Instant.now();
        FsrsParameterVersion candidate =
                fsrsParameterVersionDao.save(
                        FsrsParameterVersion.createGlobalCandidate(
                                objectMapper.writeValueAsString(result.weights()),
                                result.sampleCount(),
                                objectMapper.writeValueAsString(metricsOf(result)),
                                actorId,
                                now,
                                "SGD 优化目标用户 " + targetUserId + " 的复习日志"));
        log.info(
                "fsrs_optimized targetUserId={} candidateId={} samples={} initialLoss={} finalLoss={}",
                targetUserId,
                candidate.getId(),
                result.sampleCount(),
                String.format("%.4f", result.initialLoss()),
                String.format("%.4f", result.finalLoss()));
        return toView(candidate);
    }

    @Override
    public List<FsrsParameterVersionView> listParameters() {
        return fsrsParameterVersionDao.findAllByRecency().stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public FsrsParameterVersionView enable(Long versionId, Long actorId) {
        FsrsParameterVersion candidate =
                fsrsParameterVersionDao
                        .findById(versionId)
                        .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "权重版本不存在"));
        if (!FsrsParameterVersion.STATUS_CANDIDATE.equals(candidate.getStatus())) {
            // 状态机边界：ACTIVE 重复启用与 RETIRED 回滚都不在本端点（回滚=把旧候选重新训练/管理流程另议）。
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "仅 CANDIDATE 状态可启用");
        }
        // 同一事务：先把旧 ACTIVE 即时退役（bulk UPDATE 立即落库），再激活候选——
        // 若走实体 save 的延迟 flush，两条 UPDATE 的执行顺序不受控，会撞 uk_fsrs_active_owner。
        fsrsParameterVersionDao.demoteActiveGlobal();
        candidate.activate();
        FsrsParameterVersion saved = fsrsParameterVersionDao.save(candidate);
        OperationLogContext.set(
                "fsrs_parameter_version",
                String.valueOf(saved.getId()),
                "启用 FSRS 候选权重（样本 " + saved.getSampleCount() + " 条）");
        log.info(
                "fsrs_enabled versionId={} actorId={} sampleCount={}",
                saved.getId(),
                actorId,
                saved.getSampleCount());
        return toView(saved);
    }

    /**
     * 训练摘要（DATA-MODEL §6.9 形状 + M2.1 SGD 超参，便于复现）。
     *
     * @param result 优化结果
     * @return metrics 对象
     */
    private Map<String, Object> metricsOf(FsrsOptimizer.Result result) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("schemaVersion", 1);
        metrics.put("sampleCount", result.sampleCount());
        metrics.put("lossEvents", result.lossEvents());
        metrics.put("logLoss", result.finalLoss());
        metrics.put("rmse", result.rmse());
        metrics.put("optimizer", FsrsOptimizer.OPTIMIZER_ID);
        metrics.put("optimizerVersion", FsrsOptimizer.OPTIMIZER_VERSION);
        metrics.put("iterations", result.iterations());
        metrics.put("learningRate", FsrsOptimizer.LEARNING_RATE);
        metrics.put("initialLoss", result.initialLoss());
        return metrics;
    }

    /**
     * 实体 → 视图（weights/metrics 解码；解码失败按内部错误拒绝，不吞脏数据）。
     *
     * @param version 版本行
     * @return 视图
     */
    private FsrsParameterVersionView toView(FsrsParameterVersion version) {
        List<Double> weights = new ArrayList<>();
        for (double weight : parseWeights(version.getWeights())) {
            weights.add(weight);
        }
        Map<String, Object> metrics =
                version.getMetrics() == null
                        ? null
                        : objectMapper.readValue(version.getMetrics(), new TypeReference<>() {});
        return new FsrsParameterVersionView(
                version.getId(),
                version.getAlgorithm(),
                version.getOwnerType(),
                version.getStatus(),
                weights,
                version.getSampleCount(),
                metrics,
                version.getOptimizedAt(),
                version.getCreatedAt(),
                version.getReason());
    }

    /**
     * weights JSON → 数组。
     *
     * @param weightsJson JSON 原文
     * @return 权重数组
     */
    private double[] parseWeights(String weightsJson) {
        try {
            return objectMapper.readValue(weightsJson, double[].class);
        } catch (RuntimeException exception) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "FSRS 权重 JSON 不合法");
        }
    }
}
