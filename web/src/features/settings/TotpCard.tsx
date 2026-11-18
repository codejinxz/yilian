import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorState, Notice } from "@/components/feedback";
import { activateTotp, disableTotp, getTotpStatus, setupTotp } from "@/lib/api/totp";
import { queryKeys } from "@/lib/query";
import { disableTotpRequestSchema } from "@/lib/schemas/account";

/**
 * 注册流程的四个阶段：idle=仅展示状态；password=密码确认；secret=展示密钥与 otpauth 并
 * 等激活码；codes=一次性恢复码展示（已抄写确认后才关闭）。disable 关闭流程内嵌在 idle 态。
 */
type EnrollStage = "idle" | "password" | "secret" | "codes";

const fieldLabelClass = "mb-[9px] block font-mono text-[9px] tracking-[0.18em] text-ink-faint";
const fieldInputClass =
  "w-full max-w-xs border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition-colors placeholder:text-ink-faint focus:border-vermilion disabled:cursor-not-allowed disabled:opacity-60";

/**
 * 设置页「两步验证」卡片：状态查询 + 注册两步（setup → activate，密钥与恢复码各展示一次）+
 * 关闭双确认。登录用户可见；游客由设置页整体按游客态处理，本卡片不渲染。
 *
 * 安全口径：密钥/恢复码明文只在对应阶段短暂渲染，复制用 clipboard API，不在页面常驻；
 * 关闭需要密码 + 当前动态码双确认（验证器丢失时可用恢复码替代动态码）。
 */
export function TotpCard() {
  const queryClient = useQueryClient();
  const statusQuery = useQuery({
    queryKey: queryKeys.totpStatus,
    queryFn: ({ signal }) => getTotpStatus(signal),
  });
  const [stage, setStage] = useState<EnrollStage>("idle");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [disabling, setDisabling] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  /** 状态查询失效：注册/关闭完成后刷新卡片与剩余恢复码数。 */
  function invalidateStatus() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.totpStatus });
  }

  const setupMutation = useMutation({
    mutationFn: () => setupTotp(password),
    onSuccess: (view) => {
      setSetup(view);
      setStage("secret");
      setPassword("");
      setError(null);
      setMessage(null);
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : "注册失败，请重试"),
  });

  const activateMutation = useMutation({
    mutationFn: () => activateTotp(code),
    onSuccess: (view) => {
      setRecoveryCodes(view.recoveryCodes);
      setStage("codes");
      setCode("");
      setError(null);
      invalidateStatus();
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : "激活失败，请重试"),
  });

  const disableMutation = useMutation({
    mutationFn: (input: { password: string; code: string }) => disableTotp(input),
    onSuccess: () => {
      setDisabling(false);
      setPassword("");
      setCode("");
      setError(null);
      setMessage("两步验证已关闭，下次登录不再需要动态码。");
      invalidateStatus();
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : "关闭失败，请重试"),
  });

  /** 关闭提交：zod 校验后走双确认 mutation。 */
  function handleDisableSubmit() {
    const parsed = disableTotpRequestSchema.safeParse({ password, code });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? "请检查输入");
      return;
    }
    setError(null);
    disableMutation.mutate(parsed.data);
  }

  /** 复制 otpauth URI 全文；clipboard 不可用时如实提示（不伪造复制成功）。 */
  async function copyOtpauthUri() {
    if (!setup) {
      return;
    }
    try {
      await navigator.clipboard.writeText(setup.otpauthUri);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setError("复制失败：请手动全选 otpauth 文本复制。");
    }
  }

  if (statusQuery.isPending) {
    return (
      <p className="text-[13px] text-ink-muted" aria-live="polite">
        正在读取两步验证状态…
      </p>
    );
  }
  if (statusQuery.isError) {
    return <ErrorState error={statusQuery.error} onRetry={() => void statusQuery.refetch()} />;
  }

  const status = statusQuery.data;

  return (
    <div className="space-y-3">
      {stage === "idle" && !disabling ? (
        <>
          <p className="text-[13px] leading-[1.7] text-ink-muted">
            {status.enabled
              ? `已开启。登录需要验证器动态码；剩余恢复码 ${status.remainingRecoveryCodes} 个，用一枚少一枚。`
              : "未开启。开启后登录在密码之后需要验证器动态码，公网环境强烈建议管理员开启。"}
          </p>
          <div className="flex flex-wrap items-center gap-x-[18px] gap-y-3">
            {status.enabled ? (
              <button
                type="button"
                className="linkish"
                onClick={() => {
                  setDisabling(true);
                  setError(null);
                  setMessage(null);
                }}
              >
                关闭两步验证
              </button>
            ) : (
              <button
                type="button"
                className="linkish"
                onClick={() => {
                  setStage("password");
                  setError(null);
                  setMessage(null);
                }}
              >
                开启两步验证
              </button>
            )}
          </div>
          {message ? <Notice tone="info">{message}</Notice> : null}
        </>
      ) : null}

      {stage === "password" ? (
        <div className="space-y-3">
          <p className="text-[13px] leading-[1.7] text-ink-muted">
            先确认当前密码，再生成密钥：密钥与恢复码都只展示一次，请准备好验证器 App（Google
            Authenticator 等）。
          </p>
          <div>
            <label htmlFor="totp-setup-password" className={fieldLabelClass}>
              当前密码
            </label>
            <input
              id="totp-setup-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className={fieldInputClass}
            />
          </div>
          <div className="flex flex-wrap items-center gap-x-[18px] gap-y-3">
            <button
              type="button"
              className="linkish"
              disabled={setupMutation.isPending}
              aria-busy={setupMutation.isPending}
              onClick={() => {
                if (!password) {
                  setError("请输入当前密码");
                  return;
                }
                setError(null);
                setupMutation.mutate();
              }}
            >
              {setupMutation.isPending ? "生成中…" : "生成密钥"}
            </button>
            <button
              type="button"
              className="linkish"
              onClick={() => {
                setStage("idle");
                setPassword("");
                setError(null);
              }}
            >
              取消
            </button>
          </div>
        </div>
      ) : null}

      {stage === "secret" && setup ? (
        <div className="space-y-3">
          <p className="text-[13px] leading-[1.7] text-ink-muted">
            在验证器 App 里「手动录入密钥」，或把下面的 otpauth
            文本粘到支持导入的验证器（不渲染二维码图片，避免额外引入渲染依赖）。
          </p>
          <p className="break-all font-mono text-[12px] leading-[1.8] tracking-[0.04em] text-ink">
            {setup.secret}
          </p>
          <p className="break-all font-mono text-[10.5px] leading-[1.8] tracking-[0.02em] text-ink-faint">
            {setup.otpauthUri}
          </p>
          <div className="flex flex-wrap items-center gap-x-[18px] gap-y-3">
            <button type="button" className="linkish" onClick={() => void copyOtpauthUri()}>
              {copied ? "已复制" : "复制 otpauth 文本"}
            </button>
            <button
              type="button"
              className="linkish"
              onClick={() => {
                setStage("idle");
                setSetup(null);
                setError(null);
              }}
            >
              取消
            </button>
          </div>
          <div>
            <label htmlFor="totp-activate-code" className={fieldLabelClass}>
              验证器当前 8 位动态码
            </label>
            <input
              id="totp-activate-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={8}
              value={code}
              onChange={(event) => setCode(event.target.value)}
              className={fieldInputClass}
            />
          </div>
          <button
            type="button"
            className="btn primary"
            disabled={activateMutation.isPending}
            aria-busy={activateMutation.isPending}
            onClick={() => {
              if (!/^\d{8}$/.test(code.trim())) {
                setError("验证码为 8 位数字");
                return;
              }
              setError(null);
              activateMutation.mutate();
            }}
          >
            {activateMutation.isPending ? "激活中…" : "验证并开启"}
          </button>
        </div>
      ) : null}

      {stage === "codes" && recoveryCodes ? (
        <div className="space-y-3">
          <Notice tone="danger">
            一次性恢复码只在现在显示一次：请抄写或存进密码管理器。验证器丢失时，每枚恢复码可代替动态码登录一次。
          </Notice>
          <ul className="grid max-w-xs grid-cols-2 gap-x-6 gap-y-1.5 font-mono text-[12.5px] text-ink">
            {recoveryCodes.map((value) => (
              <li key={value}>{value}</li>
            ))}
          </ul>
          <button
            type="button"
            className="btn primary"
            onClick={() => {
              setStage("idle");
              setRecoveryCodes(null);
              setMessage("两步验证已开启。");
            }}
          >
            我已抄写保存
          </button>
        </div>
      ) : null}

      {disabling ? (
        <div className="space-y-3">
          <p className="text-[13px] leading-[1.7] text-ink-muted">
            关闭后登录只验密码。请输入当前密码与验证器当前动态码（验证器丢失可用剩余恢复码）双确认。
          </p>
          <div>
            <label htmlFor="totp-disable-password" className={fieldLabelClass}>
              当前密码
            </label>
            <input
              id="totp-disable-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className={fieldInputClass}
            />
          </div>
          <div>
            <label htmlFor="totp-disable-code" className={fieldLabelClass}>
              当前动态码（或恢复码）
            </label>
            <input
              id="totp-disable-code"
              inputMode="numeric"
              maxLength={8}
              value={code}
              onChange={(event) => setCode(event.target.value)}
              className={fieldInputClass}
            />
          </div>
          <div className="flex flex-wrap items-center gap-x-[18px] gap-y-3">
            <button
              type="button"
              className="btn primary"
              disabled={disableMutation.isPending}
              aria-busy={disableMutation.isPending}
              onClick={handleDisableSubmit}
            >
              {disableMutation.isPending ? "关闭中…" : "确认关闭"}
            </button>
            <button
              type="button"
              className="linkish"
              onClick={() => {
                setDisabling(false);
                setPassword("");
                setCode("");
                setError(null);
              }}
            >
              取消
            </button>
          </div>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="text-xs leading-5 text-vermilion-deep">
          {error}
        </p>
      ) : null}
    </div>
  );
}
