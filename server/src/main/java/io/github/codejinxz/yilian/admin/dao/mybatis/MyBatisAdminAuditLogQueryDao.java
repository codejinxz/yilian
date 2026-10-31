package io.github.codejinxz.yilian.admin.dao.mybatis;

import io.github.codejinxz.yilian.admin.dao.AdminAuditLogQueryDao;
import io.github.codejinxz.yilian.admin.dao.AuditActivityRow;

import org.springframework.stereotype.Repository;

import java.util.List;

/** {@link AdminAuditLogQueryDao} 的 MyBatis 适配器；分区表只读，LIMIT 前置校验。 */
@Repository
public class MyBatisAdminAuditLogQueryDao implements AdminAuditLogQueryDao {

    private final AdminAuditLogQueryMapper mapper;

    /**
     * 注入 Mapper 代理。
     *
     * @param mapper MyBatis Mapper
     */
    public MyBatisAdminAuditLogQueryDao(AdminAuditLogQueryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 取最近的管理活动。
     *
     * @param limit 最多返回条数，必须为正
     * @return 活动行
     * @throws IllegalArgumentException limit 非正时抛出（LIMIT 无守卫语义，契约前置校验）
     */
    @Override
    public List<AuditActivityRow> findRecent(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        return mapper.findRecent(limit);
    }
}
