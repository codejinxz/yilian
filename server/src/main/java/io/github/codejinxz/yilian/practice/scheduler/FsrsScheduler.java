package io.github.codejinxz.yilian.practice.scheduler;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.practice.dao.FsrsParameterVersionDao;
import io.github.codejinxz.yilian.practice.entity.FsrsParameterVersion;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * FSRS 排程参数装配：读 GLOBAL ACTIVE 权重版本并解码为四分支公式参数。
 *
 * <p>PracticeServiceImpl 排程只经由此处取权重，不直接触碰权重 JSON——解码规则集中在 {@link FsrsScheduleParams}，权重形状演进（原型种子 →
 * 优化器 21 维）只改一处。
 */
@Component
public class FsrsScheduler {

    private final FsrsParameterVersionDao fsrsParameterVersionDao;

    private final ObjectMapper objectMapper;

    /**
     * @param fsrsParameterVersionDao 权重版本 Dao
     * @param objectMapper JSON 解析器
     */
    public FsrsScheduler(
            FsrsParameterVersionDao fsrsParameterVersionDao, ObjectMapper objectMapper) {
        this.fsrsParameterVersionDao = fsrsParameterVersionDao;
        this.objectMapper = objectMapper;
    }

    /**
     * 取当前 GLOBAL ACTIVE 权重版本与对应排程参数。
     *
     * <p>V9 已播种原型行，正常恒存在；缺失或权重 JSON 不合法按内部错误拒绝（50000）—— 排程是写路径核心，不能静默降级到任意常数。
     *
     * @return 激活权重（版本 id + 解码后的公式参数）
     */
    public ActiveSchedule activeGlobal() {
        FsrsParameterVersion version =
                fsrsParameterVersionDao
                        .findActiveGlobal()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ApiCode.INTERNAL_ERROR,
                                                "缺少 GLOBAL ACTIVE 的 FSRS 权重版本"));
        return new ActiveSchedule(
                version.getId(),
                FsrsScheduleParams.fromWeightsJson(version.getWeights(), objectMapper));
    }

    /**
     * 激活权重：review_logs.weights_version_id 外键与四分支公式参数。
     *
     * @param versionId 权重版本行 id
     * @param params 排程公式参数
     */
    public record ActiveSchedule(long versionId, FsrsScheduleParams params) {}
}
