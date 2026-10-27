import type { HTMLAttributes } from "react";
import { cn } from "@/lib/utils";

/**
 * 暖白纸面卡片：低对比描边与极浅阴影，避免把稿纸做成仪表盘。
 * @param props 原生 div 属性；className 追加在基础样式后
 */
export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "rounded-xl border border-rule bg-surface/70 shadow-[0_24px_70px_-56px_rgba(38,33,28,0.55)] backdrop-blur-sm",
        className,
      )}
      {...props}
    />
  );
}
