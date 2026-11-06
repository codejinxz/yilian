package io.github.codejinxz.yilian.practice.dao.mybatis;

import io.github.codejinxz.yilian.practice.service.vo.AdminAppealView;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理端申诉查询 Mapper：SQL 在 {@code mapper/AppealAdminQueryMapper.xml}。
 *
 * <p>实现细节，业务代码不要注入本接口——注入 {@code AppealAdminQueryDao} 契约。
 */
@Mapper
public interface AppealAdminQueryMapper {

    /**
     * 按条件检索申诉。
     *
     * @param status 状态过滤，可为 null
     * @param kind 类型过滤，可为 null
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前页申诉行
     */
    List<AdminAppealView> search(
            @Param("status") String status,
            @Param("kind") String kind,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 与 search 同条件的总数。
     *
     * @param status 状态过滤，可为 null
     * @param kind 类型过滤，可为 null
     * @return 总条数
     */
    long countSearch(@Param("status") String status, @Param("kind") String kind);

    /**
     * 按 id 取申诉行。
     *
     * @param id 申诉行 id
     * @return 申诉行；不存在为 null
     */
    AdminAppealView findById(@Param("id") long id);
}
