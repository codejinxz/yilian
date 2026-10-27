import type { HTMLAttributes } from "react";
import { cn } from "@/lib/utils";

type Tone = "neutral" | "vermilion" | "moss" | "ink";

const tones: Record<Tone, string> = {
  neutral: "border-rule text-ink-muted",
  vermilion: "border-vermilion/45 text-vermilion",
  moss: "border-moss/45 text-moss",
  ink: "border-ink/30 bg-ink text-paper",
};

/**
 * 小号状态标记；用于复习三档、角色、批次状态等。
 * @param props tone 决定颜色，className 追加样式
 */
export function Badge({
  className,
  tone = "neutral",
  ...props
}: HTMLAttributes<HTMLSpanElement> & { tone?: Tone }) {
  return (
    <span
      className={cn(
        "inline-flex items-center whitespace-nowrap rounded border px-2 py-0.5 font-mono text-[10px] tracking-[0.08em]",
        tones[tone],
        className,
      )}
      {...props}
    />
  );
}
