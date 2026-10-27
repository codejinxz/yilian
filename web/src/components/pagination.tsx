import { useId } from "react";
import { Select } from "@/components/ui/select";
import { cn } from "@/lib/utils";

/**
 * 生成最多 7 个页码，超出时用省略号，避免管理端出现一长串按钮。
 * @param page 当前页
 * @param totalPages 总页数
 * @returns 页码或省略号占位
 */
function pageItems(page: number, totalPages: number): Array<number | "gap"> {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, index) => index + 1);
  }
  const items: Array<number | "gap"> = [1];
  const start = Math.max(2, page - 1);
  const end = Math.min(totalPages - 1, page + 1);
  if (start > 2) {
    items.push("gap");
  }
  for (let value = start; value <= end; value += 1) {
    items.push(value);
  }
  if (end < totalPages - 1) {
    items.push("gap");
  }
  items.push(totalPages);
  return items;
}

/**
 * 原型 .pager-btn 的页码按钮形态：1px 分隔线、5px 圆角；选中黑底反白（不是朱红）。
 * @param active 是否为当前页
 * @returns 可直接放入 className 的字符串
 */
function pagerButtonClass(active: boolean): string {
  return active
    ? "rounded-[5px] border border-ink bg-ink px-[9px] py-1 text-paper"
    : "rounded-[5px] border border-rule bg-transparent px-[9px] py-1 text-ink-muted transition-colors hover:border-ink-faint hover:text-ink disabled:cursor-default disabled:opacity-35 disabled:hover:border-rule disabled:hover:text-ink-muted";
}

/**
 * 统一分页器（原型 .pager 形态）：总数靠左、页码与每页条数靠右，管理端与复习页共用。
 * 筛选变化由调用方重置到第 1 页。
 * @param props page/size/total/totalPages 来自 PageResponse
 * @param props.onPageChange 页码变化
 * @param props.onSizeChange 每页条数变化（可省略，省略时不显示尺寸选择）
 * @param props.className 追加到根容器的类名
 */
export function Pagination({
  page,
  size,
  total,
  totalPages,
  hasNext,
  onPageChange,
  onSizeChange,
  className,
}: {
  page: number;
  size: number;
  total: number;
  totalPages: number;
  hasNext: boolean;
  onPageChange: (page: number) => void;
  onSizeChange?: (size: number) => void;
  className?: string;
}) {
  const resolvedTotalPages = Math.max(totalPages, 1);
  const items = pageItems(Math.min(page, resolvedTotalPages), resolvedTotalPages);
  // 显式 id 关联（useId 防止同页多个分页实例冲突）：原生 select 藏在自定义组件里，
  // 包裹式隐式关联静态分析不可见，显式 htmlFor 让可访问性关联可见且规则可判定。
  const sizeSelectId = useId();

  return (
    <nav
      aria-label="分页"
      className={cn(
        "mt-5 flex flex-wrap items-center gap-x-2 gap-y-3 text-xs text-ink-muted",
        className,
      )}
    >
      <span className="mr-auto font-mono tracking-[0.04em]">共 {total} 条</span>

      {onSizeChange ? (
        <label htmlFor={sizeSelectId} className="inline-flex items-center gap-1.5">
          每页
          <Select
            id={sizeSelectId}
            aria-label="每页条数"
            value={size}
            onChange={(event) => onSizeChange(Number(event.target.value))}
            className="rounded-[4px] px-1 py-0.5 text-xs"
          >
            {[5, 10, 20, 50].map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </Select>
        </label>
      ) : null}

      <button
        type="button"
        className={pagerButtonClass(false)}
        disabled={page <= 1}
        onClick={() => onPageChange(page - 1)}
      >
        上一页
      </button>
      {items.map((item, index) =>
        item === "gap" ? (
          <span
            // biome-ignore lint/suspicious/noArrayIndexKey: 省略号占位由 pageItems 固定算法生成且顺序稳定，index 即稳定标识。
            key={`gap-${index}`}
            aria-hidden="true"
            className="px-1 text-ink-faint"
          >
            …
          </span>
        ) : (
          <button
            key={item}
            type="button"
            className={pagerButtonClass(item === page)}
            aria-current={item === page ? "page" : undefined}
            onClick={() => onPageChange(item)}
          >
            {item}
          </button>
        ),
      )}
      <button
        type="button"
        className={pagerButtonClass(false)}
        disabled={!hasNext && page >= resolvedTotalPages}
        onClick={() => onPageChange(page + 1)}
      >
        下一页
      </button>
    </nav>
  );
}
