import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";

/**
 * 设置页专用确认弹窗：原型 askReason 的 React 版（说明 + 可选原因单选 + 确认/取消）。
 * 为什么本地实现：全局没有弹窗基础设施；「删除账号」「清空本地数据」都需要先说明后果再确认，
 * 原生 confirm 无法承载原因选择，也不符合稿纸视觉语言。
 * 视觉逐值.modal：430px 纸面盒、1px rule 边框、26/26/22 内边距、无圆角、rise 入场。
 * @param props open 显隐、title 标题、description 说明、reasons 可选原因单选（取原型 askReason 文案）、
 *              confirmLabel 确认文案、onConfirm 携带所选原因的确认回调、onCancel 取消回调
 */
export function ConfirmDialog({
  open,
  title,
  description,
  reasons,
  confirmLabel = "确认",
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  description: string;
  reasons?: readonly string[];
  confirmLabel?: string;
  onConfirm: (reason: string | null) => void;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState<string | null>(reasons?.[0] ?? null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const radioName = useId();

  // 打开时把焦点移入弹窗（落在容器上，回车不会误触危险按钮），并重置原因选择。
  useEffect(() => {
    if (open) {
      setReason(reasons?.[0] ?? null);
      dialogRef.current?.focus();
    }
  }, [open, reasons]);

  // Escape 关闭；监听挂在 window 上，因为焦点可能还留在触发按钮上。
  useEffect(() => {
    if (!open) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onCancel();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onCancel]);

  if (!open) {
    return null;
  }

  return createPortal(
    <div className="fixed inset-0 z-[80] flex items-center justify-center p-6">
      {/* 遮罩取原型 .modal-mask 精确值 rgba(20,19,16,.42)。 */}
      <div
        aria-hidden="true"
        className="absolute inset-0 bg-[rgba(20,19,16,0.42)]"
        onClick={onCancel}
      />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className="animate-[rise_0.22s_ease_both] relative w-[430px] max-w-full border border-rule bg-paper px-[26px] pt-[26px] pb-[22px] outline-none"
      >
        <h3 className="mb-2 font-serif text-[20px] font-medium text-ink">{title}</h3>
        <p className="mb-[18px] text-[12.5px] leading-[1.75] text-ink-muted">{description}</p>
        {reasons ? (
          <div>
            {reasons.map((item) => (
              <label
                key={item}
                className="mb-2.5 flex cursor-pointer items-start gap-2.5 text-[13px] leading-[1.5] text-ink-muted last:mb-0"
              >
                <input
                  type="radio"
                  name={radioName}
                  checked={reason === item}
                  onChange={() => setReason(item)}
                  className="mt-[3px] accent-vermilion"
                />
                <span>{item}</span>
              </label>
            ))}
          </div>
        ) : null}
        <div className="mt-[22px] flex justify-end gap-3">
          <button type="button" className="btn ghost" onClick={onCancel}>
            取消
          </button>
          <button type="button" className="btn primary" onClick={() => onConfirm(reason)}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
