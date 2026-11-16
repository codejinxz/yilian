package io.github.codejinxz.yilian.practice.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.practice.dao.AppealAdminQueryDao;
import io.github.codejinxz.yilian.practice.dao.AppealDao;
import io.github.codejinxz.yilian.practice.entity.Appeal;
import io.github.codejinxz.yilian.practice.service.AdminAppealService;
import io.github.codejinxz.yilian.practice.service.vo.AdminAppealView;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 管理端申诉终审实现：检索走 MyBatis 只读查询，终审走 JPA 定向 UPDATE（只碰裁决列）。
 *
 * <p>裁决口径（DATA-MODEL §5.10）：仅 PENDING 可终审；ACCEPTED 的 APPEAL 由判分 overlay 消费， REJECTED 不回写题目；初裁
 * decision JSON（AI 意见）保留不覆盖。status 流转只有 PENDING→(ACCEPTED|REJECTED)， 无 WITHDRAWN 入口（登记 backlog）。
 */
@Service
public class AdminAppealServiceImpl implements AdminAppealService {

    /** 每页条数缺省值（011 分页口径同款）。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限；前端可传更大值，越界归 100 而不是报错。 */
    static final int MAX_PAGE_SIZE = 100;

    /** 页码上限：offset = (page-1)*size 需落在 int 内，同时拦住无意义的深翻页（011 口径）。 */
    private static final int MAX_PAGE = 10_000;

    /** 状态过滤合法取值；WITHDRAWN 无入口但存量/枚举在案，筛选放行。 */
    private static final Set<String> STATUS_FILTERS =
            Set.of("PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN");

    /** 类型过滤合法取值。 */
    private static final Set<String> KIND_FILTERS = Set.of("APPEAL", "DISPUTE");

    /** 终审结论合法取值。 */
    private static final Set<String> DECISION_STATUSES = Set.of("ACCEPTED", "REJECTED");

    private final AppealAdminQueryDao appealAdminQueryDao;

    private final AppealDao appealDao;

    /**
     * @param appealAdminQueryDao 申诉只读查询 Dao（MyBatis）
     * @param appealDao 申诉 Dao（终审定向 UPDATE）
     */
    public AdminAppealServiceImpl(AppealAdminQueryDao appealAdminQueryDao, AppealDao appealDao) {
        this.appealAdminQueryDao = appealAdminQueryDao;
        this.appealDao = appealDao;
    }

    @Override
    public PageResponse<AdminAppealView> list(String status, String kind, int page, int size) {
        String statusFilter = normalizeFilter(status, STATUS_FILTERS, "status");
        String kindFilter = normalizeFilter(kind, KIND_FILTERS, "kind");
        if (page < 1 || page > MAX_PAGE || size < 1) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "分页参数不合法");
        }
        int pageSize = Math.min(size, MAX_PAGE_SIZE);
        int offset = Math.multiplyExact(pageSize, page - 1);
        List<AdminAppealView> items =
                appealAdminQueryDao.search(statusFilter, kindFilter, offset, pageSize);
        long total = appealAdminQueryDao.countSearch(statusFilter, kindFilter);
        return PageResponse.of(new ArrayList<>(items), page, pageSize, total);
    }

    @Override
    public AdminAppealView decide(long appealId, String status, String reason, Long operatorId) {
        if (!DECISION_STATUSES.contains(status)) {
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED, "status 只能为 ACCEPTED 或 REJECTED");
        }
        Appeal appeal =
                appealDao
                        .findById(appealId)
                        .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND));
        // 终审是 PENDING 的唯一出口：非 PENDING 说明已被终审（或存量异常），按冲突拒绝让管理员刷新，
        // 不做幂等重放——终审理由可能不同，静默吞掉第二次裁决会丢审计线索。
        if (!"PENDING".equals(appeal.getStatus())) {
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "该申诉已裁决，请刷新后查看");
        }
        int updated =
                appealDao.updateFinalDecision(appealId, status, reason.strip(), Instant.now());
        if (updated == 0) {
            // 029 并发原子门：UPDATE 的 WHERE 携带 status='PENDING'，行数为 0 说明预检之后记录已被
            // 并发终审（记录本身在上面的 findById 已排除不存在），按冲突拒绝而不是 NOT_FOUND——
            // 先到者的终审理由与时间不被覆盖，后到者拿到 40900 刷新即可看到先到者的结论。
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "该申诉已被终审");
        }
        AdminAppealView view = appealAdminQueryDao.findById(appealId);
        if (view == null) {
            throw new BusinessException(ApiCode.NOT_FOUND);
        }
        return view;
    }

    /**
     * 过滤参数归一：空白视为不过滤；非法取值按 40000 拒绝（与练习端 state 解析同口径， 静默归全会让管理员误以为筛选生效）。
     *
     * @param value 原始参数，可空
     * @param allowed 合法取值
     * @param field 字段名（错误文案用）
     * @return 归一后的过滤值；不过滤为 null
     */
    private static String normalizeFilter(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, field + " 取值不合法");
        }
        return normalized;
    }
}
