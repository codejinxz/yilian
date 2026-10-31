package io.github.codejinxz.yilian.admin.dao.mybatis;

import io.github.codejinxz.yilian.admin.dao.AuditActivityRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理活动查询 Mapper：SQL 在 {@code mapper/AdminAuditLogQueryMapper.xml}。
 *
 * <p>实现细节，业务代码不要注入本接口——注入 {@code AdminAuditLogQueryDao} 契约。
 */
@Mapper
public interface AdminAuditLogQueryMapper {

    /**
     * 取最近的管理活动。
     *
     * @param limit 最多返回条数
     * @return 活动行，按 created_at 倒序
     */
    List<AuditActivityRow> findRecent(@Param("limit") int limit);
}
