import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

/**
 * 原生 select 的统一外观；保留原生交互，移动端不引入额外弹层依赖。
 * @param props 原生 select 属性；className 追加在基础样式后
 */
export function Select({ className, ...props }: ComponentProps<"select">) {
  return (
    <select
      className={cn(
        "rounded-md border border-rule bg-surface px-3 py-2 text-sm text-ink outline-none transition focus:border-vermilion focus:ring-2 focus:ring-vermilion/15 disabled:cursor-not-allowed disabled:opacity-60",
        className,
      )}
      {...props}
    />
  );
}
