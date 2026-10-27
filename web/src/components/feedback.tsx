import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ApiError } from "@/lib/api";
import { cn } from "@/lib/utils";

/**
 * 页面标题区：小号朱红引题 + 衬线标题 + 说明，右侧放主操作。
 * @param props kicker 引题、title 标题、description 说明、actions 右侧操作
 */
export function PageHeader({
  kicker,
  title,
  description,
  actions,
}: {
  kicker?: string;
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-5">
      <div className="min-w-0">
        {kicker ? (
          <p className="font-mono text-[10px] uppercase tracking-[0.28em] text-vermilion">
            {kicker}
          </p>
        ) : null}
        <h1 className="mt-2 font-serif text-3xl tracking-tight text-ink sm:text-4xl">{title}</h1>
        {description ? (
          <p className="mt-3 max-w-xl text-sm leading-7 text-ink-muted">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-3">{actions}</div> : null}
    </header>
  );
}

/**
 * 行内加载占位；不使用全屏 spinner，避免慢请求把整页卡死。
 * @param props label 提示文案、rows 骨架行数
 */
export function LoadingBlock({ label = "正在加载…", rows = 3 }: { label?: string; rows?: number }) {
  return (
    <div role="status" aria-live="polite" className="space-y-3 py-4">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, index) => (
        <div
          // biome-ignore lint/suspicious/noArrayIndexKey: 骨架屏没有稳定业务键，顺序固定即可。
          key={index}
          className="h-4 animate-pulse rounded bg-ink/10"
          style={{ width: `${88 - index * 12}%` }}
        />
      ))}
      <p className="pt-1 font-mono text-[10px] tracking-[0.16em] text-ink-faint">{label}</p>
    </div>
  );
}

/**
 * 空状态；练习空队列、列表无结果都复用。
 * @param props title 标题、description 说明、action 可选操作
 */
export function EmptyState({
  title,
  description,
  action,
  className,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <Card className={cn("flex flex-col items-start gap-4 p-8", className)}>
      <p className="font-serif text-2xl text-ink">{title}</p>
      {description ? (
        <p className="max-w-prose text-sm leading-7 text-ink-muted">{description}</p>
      ) : null}
      {action}
    </Card>
  );
}

/**
 * 错误状态：区分服务端 403、离线/网络失败与普通错误，并始终提供重试。
 * @param props error 原始错误、onRetry 重试回调、title 可覆盖标题、compact 紧凑模式
 */
export function ErrorState({
  error,
  onRetry,
  title,
  compact = false,
  className,
}: {
  error: unknown;
  onRetry?: () => void;
  title?: string;
  compact?: boolean;
  className?: string;
}) {
  const denied = error instanceof ApiError && error.status === 403;
  const offline = error instanceof ApiError && error.status === 0;
  const heading =
    title ?? (denied ? "服务端拒绝访问" : offline ? "网络暂时不可用" : "页面暂时打不开");
  const message =
    error instanceof ApiError
      ? error.message
      : error instanceof Error
        ? error.message
        : "网络或服务异常，请稍后重试。";

  return (
    <Card
      role="alert"
      className={cn("border-vermilion/25 bg-vermilion/5 p-6", compact && "p-4", className)}
    >
      <p className="font-serif text-xl text-ink">{heading}</p>
      <p className="mt-2 text-sm leading-6 text-vermilion-deep">{message}</p>
      {denied ? (
        <p className="mt-2 text-xs leading-6 text-ink-muted">
          管理员接口由服务端强制鉴权；前端隐藏入口不算权限。请确认当前账号仍是管理员。
        </p>
      ) : null}
      {offline ? (
        <p className="mt-2 text-xs leading-6 text-ink-muted">
          请检查网络连接。已经填写的答案会保存在本机，联网后再提交。
        </p>
      ) : null}
      {onRetry ? (
        <Button variant="ghost" size="sm" className="mt-4" onClick={onRetry}>
          重试
        </Button>
      ) : null}
    </Card>
  );
}

type NoticeTone = "info" | "success" | "danger";

const noticeTones: Record<NoticeTone, string> = {
  info: "border-rule bg-paper-deep/60 text-ink-muted",
  success: "border-moss/35 bg-moss/5 text-moss",
  danger: "border-vermilion/30 bg-vermilion/5 text-vermilion-deep",
};

/**
 * 行内提示条；可访问的 aria-live 提示，不打断输入。
 * @param props tone 语气、children 内容
 */
export function Notice({
  tone = "info",
  children,
  className,
}: {
  tone?: NoticeTone;
  children: ReactNode;
  className?: string;
}) {
  return (
    <p
      className={cn("rounded-md border px-4 py-3 text-sm leading-6", noticeTones[tone], className)}
      role={tone === "danger" ? "alert" : "status"}
    >
      {children}
    </p>
  );
}

/**
 * 字段级错误；与 Label 搭配使用。
 * @param props.children 错误文案
 */
export function FieldError({ children }: { children?: ReactNode }) {
  if (!children) {
    return null;
  }
  return (
    <p role="alert" className="text-xs leading-5 text-vermilion-deep">
      {children}
    </p>
  );
}
