package io.github.codejinxz.yilian.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 分页响应体
 *
 * <p>空列表固定为 {@code []}，不返回 null；{@code total} 是满足筛选条件的总数，不是当前页大小。
 *
 * @param items 当前页数据
 * @param page 当前页码，从 1 开始
 * @param size 每页大小
 * @param total 总条数
 * @param totalPages 总页数
 * @param hasNext 是否还有下一页
 * @param <T> 列表元素类型
 */
@Schema(description = "分页响应：items 为空数组而不是 null，total 是筛选后的总数")
public record PageResponse<T>(
        @Schema(description = "当前页数据，空列表为 []") List<T> items,
        @Schema(description = "页码，从 1 开始") int page,
        @Schema(description = "每页大小") int size,
        @Schema(description = "满足筛选条件的总条数") long total,
        @Schema(description = "总页数") int totalPages,
        @Schema(description = "是否还有下一页") boolean hasNext) {

    /**
     * 构造分页响应，统一计算总页数与 hasNext。
     *
     * @param items 当前页数据
     * @param page 页码，从 1 开始
     * @param size 每页大小
     * @param total 总条数
     * @param <T> 元素类型
     * @return 分页响应
     */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(
                items == null ? List.of() : items,
                page,
                size,
                total,
                totalPages,
                page < totalPages);
    }
}
