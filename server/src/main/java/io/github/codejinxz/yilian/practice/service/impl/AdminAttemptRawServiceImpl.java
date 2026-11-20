package io.github.codejinxz.yilian.practice.service.impl;

import io.github.codejinxz.yilian.admin.service.AdminAuditEventService;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.practice.dao.AttemptDao;
import io.github.codejinxz.yilian.practice.dao.AttemptGradingDao;
import io.github.codejinxz.yilian.practice.entity.Attempt;
import io.github.codejinxz.yilian.practice.entity.AttemptGrading;
import io.github.codejinxz.yilian.practice.service.AdminAttemptRawService;
import io.github.codejinxz.yilian.practice.service.vo.AdminAttemptRawView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 查看答题原文实现（API §8.7）：审计行先于响应装配落库，auditId 随视图返回。
 *
 * <p>审计与数据装配在同一个事务里：装配中途失败随事务回滚，不会出现「有审计痕迹却没看成」 或反向的「看成了却无痕」。提交不存在时不落审计行——
 * 该路径没有发生查看，回滚语义下与先行落库终态一致。
 */
@Service
public class AdminAttemptRawServiceImpl implements AdminAttemptRawService {

    private final AttemptDao attemptDao;

    private final AttemptGradingDao attemptGradingDao;

    private final AdminAuditEventService adminAuditEventService;

    private final ObjectMapper objectMapper;

    /**
     * @param attemptDao 提交读 Dao
     * @param attemptGradingDao 判分读 Dao
     * @param adminAuditEventService 审计写入服务（admin 域，跨模块仅依赖其接口）
     * @param objectMapper JSON 文档解析
     */
    public AdminAttemptRawServiceImpl(
            AttemptDao attemptDao,
            AttemptGradingDao attemptGradingDao,
            AdminAuditEventService adminAuditEventService,
            ObjectMapper objectMapper) {
        this.attemptDao = attemptDao;
        this.attemptGradingDao = attemptGradingDao;
        this.adminAuditEventService = adminAuditEventService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AdminAttemptRawView viewRaw(
            String attemptId, String reason, Long actorId, String actorName, String actorRole) {
        String trimmedReason = StringUtils.defaultIfBlank(reason, null);
        if (trimmedReason == null) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "查看答题原文必须填写原因");
        }
        Attempt attempt =
                attemptDao
                        .findById(attemptId)
                        .orElseThrow(() -> new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));
        long auditId =
                adminAuditEventService.recordViewAttemptRaw(
                        actorId, actorName, actorRole, attemptId, trimmedReason.trim());
        AttemptGrading latest = attemptGradingDao.findLatestByAttemptId(attemptId).orElse(null);
        return new AdminAttemptRawView(
                attempt.getId(),
                String.valueOf(attempt.getUserId()),
                attempt.getSentenceId(),
                parseJson(attempt.getAnswer()),
                latest == null
                        ? null
                        : new AdminAttemptRawView.Grading(
                                String.valueOf(latest.getId()),
                                latest.getScore(),
                                parseJson(latest.getResult())),
                Long.toString(auditId));
    }

    /**
     * 库里的 answer/result 由服务端在写路径校验过是合法 JSON；损坏意味着数据被越权改动， 按未知异常收敛成 500 而不是把半截文档吐给管理员。
     *
     * @param json JSON 文本
     * @return 解析后的文档
     */
    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "数据文档无法解析，请稍后再试");
        }
    }
}
