import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

/**
 * 多行输入；练习整句模式用衬线正文，普通表单用无衬线。
 * @param props 原生 textarea 属性；className 追加在基础样式后
 */
export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      className={cn(
        "w-full resize-y rounded-md border border-rule bg-surface px-3.5 py-3 text-base leading-7 text-ink outline-none transition placeholder:text-ink-faint focus:border-vermilion focus:ring-2 focus:ring-vermilion/15 disabled:cursor-not-allowed disabled:opacity-60",
        className,
      )}
      {...props}
    />
  );
}
