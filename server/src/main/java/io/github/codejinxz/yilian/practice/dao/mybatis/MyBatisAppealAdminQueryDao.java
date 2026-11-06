package io.github.codejinxz.yilian.practice.dao.mybatis;

import io.github.codejinxz.yilian.practice.dao.AppealAdminQueryDao;
import io.github.codejinxz.yilian.practice.service.vo.AdminAppealView;

import org.springframework.stereotype.Repository;

import java.util.List;

/** 管理端申诉只读查询 Dao 的 MyBatis 实现。 */
@Repository
public class MyBatisAppealAdminQueryDao implements AppealAdminQueryDao {

    private final AppealAdminQueryMapper mapper;

    /**
     * @param mapper 申诉查询 Mapper
     */
    public MyBatisAppealAdminQueryDao(AppealAdminQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AdminAppealView> search(String status, String kind, int offset, int limit) {
        return mapper.search(status, kind, offset, limit);
    }

    @Override
    public long countSearch(String status, String kind) {
        return mapper.countSearch(status, kind);
    }

    @Override
    public AdminAppealView findById(long id) {
        return mapper.findById(id);
    }
}
