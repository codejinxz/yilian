package io.github.codejinxz.yilian.admin.dao;

/**
 * 概览每日活跃计数行（只读 DTO，MyBatis 直出）。
 *
 * @param date 日期，{@code YYYY-MM-DD}（库内 DATETIME 均为 UTC，DATE_FORMAT 直接产出 UTC 日期）
 * @param count 当日活跃用户数
 */
public record DailyActiveCountRow(String date, long count) {}
