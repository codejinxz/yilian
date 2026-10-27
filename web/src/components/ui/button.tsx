import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/utils";

type Variant = "primary" | "ghost" | "quiet" | "danger";
type Size = "sm" | "md";

const variants: Record<Variant, string> = {
  primary:
    "bg-vermilion text-paper hover:bg-vermilion-deep disabled:hover:bg-vermilion border border-vermilion",
  ghost:
    "border border-rule bg-transparent text-ink hover:border-vermilion/50 hover:text-vermilion",
  quiet: "border border-transparent bg-transparent text-ink-muted hover:text-ink",
  danger:
    "border border-vermilion/40 bg-transparent text-vermilion hover:bg-vermilion hover:text-paper",
};

const sizes: Record<Size, string> = {
  sm: "px-3 py-1.5 text-xs",
  md: "px-5 py-2.5 text-sm",
};

/**
 * 组合按钮样式；供原生 button 与需要呈现为按钮的 Link 复用同一套视觉。
 * @param options variant 视觉层级、size 尺寸与额外类名
 * @returns 可直接放入 className 的字符串
 */
export function buttonClassName(
  options: { variant?: Variant; size?: Size; className?: string } = {},
): string {
  const { variant = "primary", size = "md", className } = options;
  return cn(
    "inline-flex items-center justify-center gap-2 rounded-md font-medium tracking-wide transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-vermilion disabled:cursor-not-allowed disabled:opacity-55",
    variants[variant],
    sizes[size],
    className,
  );
}

/**
 * 统一按钮。primary 用于一步主操作，ghost 用于次要操作，quiet 用于文字操作。
 * @param props 原生 button 属性；variant 与 size 控制视觉层级
 */
export function Button({
  className,
  variant = "primary",
  size = "md",
  type = "button",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  size?: Size;
  children?: ReactNode;
}) {
  return (
    <button type={type} className={buttonClassName({ variant, size, className })} {...props} />
  );
}
