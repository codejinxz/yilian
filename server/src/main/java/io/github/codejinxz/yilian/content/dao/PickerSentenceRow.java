package io.github.codejinxz.yilian.content.dao;

import java.time.Instant;

/**
 * 练习选题句列表查询行（只读 DTO，MyBatis JOIN 直出）。
 *
 * <p>列别名经驼峰映射对齐构造参数；练习态来自 {@code reviews} 的 LEFT JOIN—— 匿名（userId 为 NULL）或未练过的行两列均为 NULL。列名经 {@code
 * map-underscore-to-camel-case} 映射。
 *
 * @param sentenceId 题目 ULID
 * @param zh 中文原句
 * @param level 难度，可空
 * @param status 生命周期
 * @param reviewState 当前用户卡三档状态，无卡为 NULL
 * @param reviewDueAt 当前用户卡到期时间，无卡为 NULL
 */
public record PickerSentenceRow(
        String sentenceId,
        String zh,
        String level,
        String status,
        String reviewState,
        Instant reviewDueAt) {}
