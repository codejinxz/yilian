import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

/**
 * 统一文本输入框。移动端使用 16px 字号，避免 iOS 聚焦时自动放大页面。
 * @param props 原生 input 属性；className 追加在基础样式后
 */
export function Input({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      className={cn(
        "w-full rounded-md border border-rule bg-surface px-3.5 py-2.5 text-base text-ink outline-none transition placeholder:text-ink-faint focus:border-vermilion focus:ring-2 focus:ring-vermilion/15 disabled:cursor-not-allowed disabled:opacity-60",
        className,
      )}
      {...props}
    />
  );
}
