package io.github.codejinxz.yilian.admin.dao.mybatis;

import io.github.codejinxz.yilian.admin.dao.AdminOverviewQueryDao;
import io.github.codejinxz.yilian.admin.dao.DailyActiveCountRow;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * {@link AdminOverviewQueryDao} 的 MyBatis 适配器，与 content 模块 {@code dao.mybatis} 模式一致： 契约在 {@code
 * dao} 包，实现 在 {@code dao.mybatis}，服务层无感知。
 */
@Repository
public class MyBatisAdminOverviewQueryDao implements AdminOverviewQueryDao {

    private final AdminOverviewQueryMapper mapper;

    /**
     * 注入 Mapper 代理。
     *
     * @param mapper MyBatis Mapper
     */
    public MyBatisAdminOverviewQueryDao(AdminOverviewQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countUsers() {
        return mapper.countUsers();
    }

    @Override
    public long countAttempts() {
        return mapper.countAttempts();
    }

    @Override
    public long countActiveUsersBetween(Instant from, Instant to) {
        return mapper.countActiveUsersBetween(from, to);
    }

    @Override
    public List<DailyActiveCountRow> countDailyActiveUsersBetween(Instant from, Instant to) {
        return mapper.countDailyActiveUsersBetween(from, to);
    }
}
