import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { createPortal } from "react-dom";
import { Notice } from "@/components/feedback";
import { ConfirmDialog } from "@/features/settings/confirm-dialog";
import { deleteAccount, exportAccountData } from "@/lib/api/account";
import { getTotpStatus } from "@/lib/api/totp";
import { queryKeys } from "@/lib/query";
import { type DeleteAccountReceipt, deleteAccountRequestSchema } from "@/lib/schemas/account";

/** 删号冷静期天数；与服务端配置键默认值一致，仅用于页内提示文案。 */
const GRACE_DAYS = 30;

const fieldLabelClass = "mb-[9px] block font-mono text-[9px] tracking-[0.18em] text-ink-faint";
const fieldInputClass =
  "w-full max-w-xs border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition-colors placeholder:text-ink-faint focus:border-vermilion disabled:cursor-not-allowed disabled:opacity-60";

/**
 * 删号确认弹窗：风险说明（软删 30 天冷静期 → 匿名化不可逆、无自助撤销、学习记录保留）
 * + 「先导出我的数据」（GET /api/me/export 整包 JSON 下载）+ 密码确认 + 两步验证动态码位
 * （2FA 开启时）。提交后还有一层「不可撤销」二次确认（ConfirmDialog），两次确认后才发请求。
 *
 * @param props open 显隐、onClose 取消回调、onDeleted 删号成功回调（携带回执，调用方清会话回登录页）
 */
export function DeleteAccountDialog({
  open,
  onClose,
  onDeleted,
}: {
  open: boolean;
  onClose: () => void;
  onDeleted: (receipt: DeleteAccountReceipt) => void;
}) {
  // 2FA 状态决定动态码位显隐；open 时才拉取（游客/关闭态各给兜底文案）。
  const statusQuery = useQuery({
    queryKey: queryKeys.totpStatus,
    queryFn: ({ signal }) => getTotpStatus(signal),
    enabled: open,
  });
  const totpEnabled = statusQuery.data?.enabled ?? false;

  const [password, setPassword] = useState("");
  const [totpCode, setTotpCode] = useState("");
  const [exporting, setExporting] = useState(false);
  const [exportMessage, setExportMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const mutation = useMutation({
    mutationFn: (input: { password: string; totpCode?: string }) => deleteAccount(input),
    onSuccess: (receipt) => {
      setConfirming(false);
      onClose();
      onDeleted(receipt);
    },
    onError: (cause) => {
      setConfirming(false);
      setError(cause instanceof Error ? cause.message : "删号失败，请稍后重试");
    },
  });

  /** 匿名化时点提示：今天 + 30 天（本地日期展示，与回执的 UTC 时刻口径在文案中注明）。 */
  const anonymizeDate = new Date(Date.now() + GRACE_DAYS * 86_400_000).toISOString().slice(0, 10);

  /**
   * 导出服务端账号数据并触发浏览器下载；导出失败不阻断删号流程，行内提示。
   */
  async function handleExport() {
    setExporting(true);
    try {
      const data = await exportAccountData();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `yilian-export-${new Date().toISOString().slice(0, 10)}.json`;
      anchor.click();
      URL.revokeObjectURL(url);
      setExportMessage(
        `已下载服务端账号数据（${data.attempts.length} 条答题、${data.attemptGradings.length} 条判分）。`,
      );
      setError(null);
    } catch (cause) {
      setExportMessage(
        cause instanceof Error ? `导出失败：${cause.message}` : "导出失败，请稍后重试。",
      );
    } finally {
      setExporting(false);
    }
  }

  /**
   * 第一次确认：输入校验通过后弹出「不可撤销」二次确认；二次确认里再走一次 zod 校验
   * （防止两次确认之间输入被清空后仍发请求）。
   */
  function handleFirstConfirm() {
    if (totpEnabled && totpCode.trim() === "") {
      setError("已开启两步验证，请输入当前动态码或恢复码");
      return;
    }
    const parsed = deleteAccountRequestSchema.safeParse({
      password,
      ...(totpEnabled ? { totpCode: totpCode.trim() } : {}),
    });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? "请检查输入");
      return;
    }
    setError(null);
    setConfirming(true);
  }

  if (!open) {
    return null;
  }

  return createPortal(
    <div className="fixed inset-0 z-[80] flex items-center justify-center p-6">
      <div
        aria-hidden="true"
        className="absolute inset-0 bg-[rgba(20,19,16,0.42)]"
        onClick={onClose}
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="删除账号"
        className="animate-[rise_0.22s_ease_both] relative w-[430px] max-w-full border border-rule bg-paper px-[26px] pt-[26px] pb-[22px]"
      >
        <h3 className="mb-2 font-serif text-[20px] font-medium text-ink">删除账号</h3>
        <p className="mb-[14px] text-[12.5px] leading-[1.75] text-ink-muted">
          删除后账号立即软删并退出登录，进入 {GRACE_DAYS} 天冷静期；约 {anonymizeDate}（UTC
          口径）起做匿名化处理：登录名与邮箱等身份信息将被清除或替换，该过程不可逆、无自助撤销。
          你的答题与批改记录会保留，但不再关联到可识别的身份。
        </p>
        <Notice tone="danger">
          建议先导出服务端账号数据再删号；匿名化后数据无法再找回。本机导出只含本机数据，两者互不替代。
        </Notice>
        <div className="mt-3">
          <button
            type="button"
            className="linkish"
            disabled={exporting}
            aria-busy={exporting}
            onClick={() => void handleExport()}
          >
            {exporting ? "导出中…" : "先导出我的数据（服务端）"}
          </button>
          {exportMessage ? (
            <p className="mt-2 text-xs leading-5 text-ink-faint">{exportMessage}</p>
          ) : null}
        </div>

        <div className="mt-5">
          <label htmlFor="delete-account-password" className={fieldLabelClass}>
            当前密码
          </label>
          <input
            id="delete-account-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className={fieldInputClass}
          />
        </div>
        {totpEnabled ? (
          <div className="mt-3">
            <label htmlFor="delete-account-code" className={fieldLabelClass}>
              验证器当前 8 位动态码（或恢复码）
            </label>
            <input
              id="delete-account-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={8}
              value={totpCode}
              onChange={(event) => setTotpCode(event.target.value)}
              className={fieldInputClass}
            />
          </div>
        ) : null}

        {error ? (
          <p role="alert" className="mt-3 text-xs leading-5 text-vermilion-deep">
            {error}
          </p>
        ) : null}

        <div className="mt-[22px] flex justify-end gap-3">
          <button
            type="button"
            className="btn ghost"
            onClick={() => {
              onClose();
              setPassword("");
              setTotpCode("");
              setError(null);
              setExportMessage(null);
            }}
          >
            取消
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
            onClick={handleFirstConfirm}
          >
            删除账号
          </button>
        </div>
      </div>

      <ConfirmDialog
        open={confirming}
        title="确认删除账号？"
        description={`这是最后一步：账号将立即软删并退出登录，约 ${anonymizeDate} 起匿名化（不可逆、无自助撤销）。冷却期内如需争议请联系运营者管理员。`}
        confirmLabel="确认删除"
        onCancel={() => setConfirming(false)}
        onConfirm={() => {
          const parsed = deleteAccountRequestSchema.safeParse({
            password,
            ...(totpEnabled ? { totpCode: totpCode.trim() } : {}),
          });
          if (!parsed.success) {
            setConfirming(false);
            setError(parsed.error.issues[0]?.message ?? "请检查输入");
            return;
          }
          mutation.mutate(parsed.data);
        }}
      />
    </div>,
    document.body,
  );
}
