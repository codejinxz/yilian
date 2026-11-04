package io.github.codejinxz.yilian.content.dao.mybatis;

import io.github.codejinxz.yilian.content.dao.PickerSentenceQueryDao;
import io.github.codejinxz.yilian.content.dao.PickerSentenceRow;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link PickerSentenceQueryDao} 的 MyBatis 适配器，与 {@code dao.jpa.JpaXxxDao} 模式对称： 契约在 {@code dao}
 * 包，实现在 {@code dao.mybatis}，业务层无感知。
 */
@Repository
public class MyBatisPickerSentenceQueryDao implements PickerSentenceQueryDao {

    private final PickerSentenceQueryMapper mapper;

    /**
     * 注入 Mapper 代理。
     *
     * @param mapper MyBatis Mapper
     */
    public MyBatisPickerSentenceQueryDao(PickerSentenceQueryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页取题库内选题句。
     *
     * @param bankId 题库 id，必填
     * @param userId 当前用户 id，可为 null（匿名）
     * @param keyword 关键字，可为 null
     * @param level 难度，可为 null
     * @param status 状态，可为 null
     * @param hideRecycled 是否隐藏回收站
     * @param offset 偏移量，必须非负
     * @param limit 每页条数，必须为正
     * @return 当前页行
     * @throws IllegalArgumentException limit 非正或 offset 为负时抛出（LIMIT/OFFSET 无守卫语义，契约前置校验）
     */
    @Override
    public List<PickerSentenceRow> search(
            long bankId,
            Long userId,
            String keyword,
            String level,
            String status,
            boolean hideRecycled,
            int offset,
            int limit) {
        if (limit < 1 || offset < 0) {
            throw new IllegalArgumentException(
                    "limit 必须为正且 offset 非负: offset=" + offset + ", limit=" + limit);
        }
        return mapper.search(bankId, userId, keyword, level, status, hideRecycled, offset, limit);
    }

    /**
     * 与 {@link #search} 同条件的总数。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，可为 null（匿名）
     * @param keyword 关键字，可为 null
     * @param level 难度，可为 null
     * @param status 状态，可为 null
     * @param hideRecycled 是否排除回收站
     * @return 总行数
     */
    @Override
    public long count(
            long bankId,
            Long userId,
            String keyword,
            String level,
            String status,
            boolean hideRecycled) {
        return mapper.count(bankId, userId, keyword, level, status, hideRecycled);
    }
}
