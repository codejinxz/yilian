import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

/**
 * 表单标签；小号等宽大写作为稿纸边注的视觉语言。
 * @param props 原生 label 属性
 */
export function Label({ className, ...props }: ComponentProps<"label">) {
  return (
    // biome-ignore lint/a11y/noLabelWithoutControl: 通用原生 label 包装组件，控件关联由调用方以 htmlFor 或包裹输入的方式保证。
    <label
      className={cn(
        "block text-[11px] font-medium uppercase tracking-[0.18em] text-ink-muted",
        className,
      )}
      {...props}
    />
  );
}
