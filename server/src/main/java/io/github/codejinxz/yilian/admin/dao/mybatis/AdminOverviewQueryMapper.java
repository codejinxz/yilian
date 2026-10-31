package io.github.codejinxz.yilian.admin.dao.mybatis;

import io.github.codejinxz.yilian.admin.dao.DailyActiveCountRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 概览统计查询 Mapper：SQL 在 {@code mapper/AdminOverviewQueryMapper.xml}。
 *
 * <p>实现细节，业务代码不要注入本接口——注入 {@code AdminOverviewQueryDao} 契约。
 */
@Mapper
public interface AdminOverviewQueryMapper {

    /**
     * 注册用户总数。
     *
     * @return users 表行数
     */
    long countUsers();

    /**
     * 答题总数。
     *
     * @return attempts 表行数
     */
    long countAttempts();

    /**
     * 时间窗内独立活跃用户数。
     *
     * @param from 起始时间（含，UTC）
     * @param to 截止时间（不含，UTC）
     * @return 去重用户数
     */
    long countActiveUsersBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * 按 UTC 自然日分组统计独立活跃用户数。
     *
     * @param from 起始时间（含，UTC）
     * @param to 截止时间（不含，UTC）
     * @return 按日期升序的计数行
     */
    List<DailyActiveCountRow> countDailyActiveUsersBetween(
            @Param("from") Instant from, @Param("to") Instant to);
}
