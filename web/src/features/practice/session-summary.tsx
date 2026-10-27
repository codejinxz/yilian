import { useEffect } from "react";

/** 本轮计数：页面内存态，进入练习页起算，不落库。 */
export interface RoundTally {
  /** 已完成题数（首次提交与看答案各计 1，重打不计）。 */
  done: number;
  /** 全对题数。 */
  correct: number;
  /** 错题考点 → 次数；总结弹窗据此找「最需要补的考点」。 */
  weakTags: Record<string, number>;
}

/**
 * 本轮总结弹窗：`今天到此` 的收束动作——不评判、只给出一个短板与四个数。
 * @param props.open 是否打开
 * @param props.round 本轮计数
 * @param props.dueCount 当前到期句数（来自今日快照）
 * @param props.onClose 关闭弹窗（留在练习页）
 * @param props.onHome 回到今日启动台
 * @param props.onAgain 再来一句（关闭并取下一题）
 */
export function RoundSummaryModal({
  open,
  round,
  dueCount,
  onClose,
  onHome,
  onAgain,
}: {
  open: boolean;
  round: RoundTally;
  dueCount: number;
  onClose: () => void;
  onHome: () => void;
  onAgain: () => void;
}) {
  // 弹窗打开时锁住背景滚动，与原型 showSummary 的行为一致。
  useEffect(() => {
    if (!open) {
      return;
    }
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        onClose();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.body.style.overflow = previous;
      document.removeEventListener("keydown", onKey);
    };
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  const rate = round.done > 0 ? Math.round((round.correct / round.done) * 100) : 0;
  const weakest = Object.entries(round.weakTags)
    .sort((a, b) => b[1] - a[1])
    .map(([tag]) => tag)[0];

  return (
    // z 序：modal 80 > drawer 70 > toast 60。
    <div className="fixed inset-0 z-[80] flex items-center justify-center p-6">
      <button
        type="button"
        aria-label="关闭"
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-ink/40"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="本轮总结"
        className="rise relative w-[430px] max-w-full border border-rule bg-paper px-[26px] pb-[22px] pt-[26px]"
      >
        {/* 原型 .drawer-close：右上 20px、18px 无衬线、ink3，无 hover 变色。 */}
        <button
          type="button"
          onClick={onClose}
          aria-label="关闭"
          className="absolute top-5 right-5 cursor-pointer border-none bg-transparent p-0 font-sans text-[18px] leading-none text-ink-faint"
        >
          ×
        </button>
        <p className="mb-2.5 font-mono text-[9px] tracking-[0.18em] text-ink-faint">本轮结束</p>
        <h3 className="mb-2 font-serif text-xl font-medium text-ink">今天练了 {round.done} 句</h3>
        <p className="mb-[18px] text-[12.5px] leading-[1.75] text-ink-muted">
          {weakest ? `最需要补的是「${weakest}」，明天会优先排给你。` : "这一轮没有明显短板。"}
        </p>
        <div className="mt-6 grid grid-cols-2 gap-[18px] border-y border-rule py-[22px]">
          <div>
            <b className="block font-serif text-2xl font-medium leading-none text-ink">
              {round.done}
            </b>
            <span className="mt-[7px] block font-mono text-[9px] tracking-[0.14em] text-ink-faint">
              完成
            </span>
          </div>
          <div>
            <b className="block font-serif text-2xl font-medium leading-none text-ink">
              {round.correct}
            </b>
            <span className="mt-[7px] block font-mono text-[9px] tracking-[0.14em] text-ink-faint">
              全对
            </span>
          </div>
          <div>
            <b className="block font-serif text-2xl font-medium leading-none text-ink">
              {round.done > 0 ? `${rate}%` : "—"}
            </b>
            <span className="mt-[7px] block font-mono text-[9px] tracking-[0.14em] text-ink-faint">
              正确率
            </span>
          </div>
          <div>
            <b className="block font-serif text-2xl font-medium leading-none text-ink">
              {dueCount}
            </b>
            <span className="mt-[7px] block font-mono text-[9px] tracking-[0.14em] text-ink-faint">
              当前到期
            </span>
          </div>
        </div>
        {/* 原型 .modal-actions：右对齐、间距 12px，两个 ghost + 一个 primary（全尺寸 .btn）。 */}
        <div className="mt-[22px] flex justify-end gap-3">
          <button type="button" onClick={onHome} className="btn ghost">
            回到今日
          </button>
          <button type="button" onClick={onClose} className="btn ghost">
            关闭
          </button>
          <button type="button" onClick={onAgain} className="btn primary">
            再来一句
          </button>
        </div>
      </div>
    </div>
  );
}
