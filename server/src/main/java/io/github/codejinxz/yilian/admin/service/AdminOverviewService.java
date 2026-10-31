package io.github.codejinxz.yilian.admin.service;

import io.github.codejinxz.yilian.admin.service.vo.AdminOverviewView;

/** 管理端概览聚合用例：指标、近 7 天活跃、待办引导与最近管理活动。 */
public interface AdminOverviewService {

    /**
     * 聚合概览数据；纯读路径，无副作用。
     *
     * @return 概览视图
     */
    AdminOverviewView overview();
}
