package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 管理端 provider 目录列表信封（API §8.15.1）：data 收口为 {@code {items: [...]}}， 不以裸数组直出——前端 admin.ts 按信封内 items
 * 解析，裸数组会让模型页整页不可用（022 巡检 DEF-01）。
 *
 * @param items 目录条目（保持目录顺序，可为空）
 */
@Schema(description = "provider 目录列表：统一信封内 items 数组，空目录时 items 为空数组")
public record LlmProviderListView(
        @Schema(description = "目录条目列表（保持目录顺序）", requiredMode = Schema.RequiredMode.REQUIRED)
                List<LlmProviderView> items) {

    /**
     * 便捷工厂：把服务层目录列表包成信封。
     *
     * @param items 目录条目列表
     * @return 列表信封
     */
    public static LlmProviderListView of(List<LlmProviderView> items) {
        return new LlmProviderListView(items);
    }
}
