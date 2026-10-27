import { useEffect, useRef } from "react";
import { cn } from "@/lib/utils";

/** 难度代码 → 中文标签；与练习页 qmeta 同一套映射，未知代码原样展示（自建难度）。 */
const LEVEL_LABELS: Record<string, string> = {
  CET4: "四级",
  CET6: "六级",
  TEM4: "专四",
  TEM8: "专八",
  IELTS: "雅思",
  TOEFL: "托福",
  CUSTOM: "自建",
};

/** 抽屉行的句子数据；只需要跳句与展示的最小字段。 */
export interface PeekSentence {
  id: string;
  zh: string;
  /** 难度代码；行尾 meta 以「级别 · 状态」连排展示。 */
  level: string | null;
}

/**
 * 抽屉动效：原型 .drawer（右移入 24px、.26s、cubic-bezier(.2,.8,.2,1)）与
 * .drawer-mask（渐显 .2s ease）。styles.css 本轮不可改，keyframes 随组件内联声明；
 * prefers-reduced-motion 下全局样式会把所有动画压到 0.001ms，无需单独降级。
 */
const DRAWER_STYLE = `
  .yilian-drawer-panel { animation: yilian-drawer-slide 0.26s cubic-bezier(0.2, 0.8, 0.2, 1) both; }
  .yilian-drawer-mask { animation: yilian-drawer-fade 0.2s ease both; }
  @keyframes yilian-drawer-slide {
    from { transform: translateX(24px); opacity: 0; }
    to { transform: none; opacity: 1; }
  }
  @keyframes yilian-drawer-fade {
    from { opacity: 0; }
    to { opacity: 1; }
  }
`;

/**
 * 题库抽屉（原型 openBankPeek）：列出当前题库全部句子，点击跳到该句练习。
 * 右侧滑入、带遮罩，Escape 或点击遮罩关闭；跳句动作由调用方执行（含状态清理）。
 * @param props.open 是否打开
 * @param props.bankId 题库 id（kicker 行展示，原型用 b.id）
 * @param props.bankName 题库名
 * @param props.bankMeta 题库说明（句数等元信息行）
 * @param props.sentences 题库句子（已过滤删除行）
 * @param props.currentSentenceId 当前练习的句子 id
 * @param props.queuedIds 已进入复习队列的句子 id 集合（引擎「错才入队」，全对首练不建卡）
 * @param props.sentencesLoading 题库句子还在加载时显示占位
 * @param props.onJump 点击句子行的回调
 * @param props.onClose 关闭回调
 * @returns 抽屉；open=false 时不渲染
 */
export function BankPeekDrawer({
  open,
  bankId,
  bankName,
  bankMeta,
  sentences,
  currentSentenceId,
  queuedIds,
  sentencesLoading,
  onJump,
  onClose,
}: {
  open: boolean;
  bankId: string | null;
  bankName: string | null;
  bankMeta: string | null;
  sentences: PeekSentence[];
  currentSentenceId: string | null;
  queuedIds: ReadonlySet<string>;
  sentencesLoading: boolean;
  onJump: (sentenceId: string) => void;
  onClose: () => void;
}) {
  const panelRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    // Escape 关闭与原型 closeDrawer 一致；监听 document，抽屉内焦点位置无关紧要。
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    // 打开期间锁正文滚动，避免抽屉下的练习页跟着滚。
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    panelRef.current?.focus();
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-[70] flex justify-end"
      role="dialog"
      aria-modal="true"
      aria-label={`题库 ${bankName ?? ""} 的句子列表`}
    >
      <style>{DRAWER_STYLE}</style>
      <button
        type="button"
        aria-label="关闭题库抽屉"
        onClick={onClose}
        className="yilian-drawer-mask absolute inset-0 cursor-default border-none bg-ink/35 p-0"
      />
      <aside
        ref={panelRef}
        tabIndex={-1}
        // 尺寸与内边距逐值.drawer：440px、右缘线、padding 34px 32px 64px。
        className="yilian-drawer-panel relative h-full w-[440px] max-w-[94vw] overflow-auto border-l border-rule bg-paper px-8 pb-16 pt-[34px] outline-none"
      >
        <button
          type="button"
          onClick={onClose}
          aria-label="关闭"
          className="absolute top-5 right-5 cursor-pointer border-none bg-transparent p-0 font-sans text-[18px] leading-none text-ink-faint"
        >
          ×
        </button>
        {/* 原型 drawer-kicker 用题库 id 而非名称：id 稳定、名称可改。 */}
        <p className="mb-2.5 font-mono text-[9px] tracking-[0.18em] text-ink-faint">
          题库 · {bankId ?? ""}
        </p>
        <h2 className="m-0 mb-[5px] font-serif text-[26px] font-medium leading-tight text-ink">
          {bankName ?? "题库"}
        </h2>
        {bankMeta ? (
          <p className="m-0 font-mono text-[11px] tracking-[0.04em] text-ink-faint">{bankMeta}</p>
        ) : null}
        {/* 原型 .drawer .section-title：上边距 30px，全局类自带 16px 下边距。 */}
        <p className="section-title mt-[30px]">题目</p>
        <div>
          {sentencesLoading ? (
            <p className="m-0 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
              正在载入句子…
            </p>
          ) : sentences.length === 0 ? (
            <p className="m-0 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
              这个题库还没有句子。
            </p>
          ) : (
            sentences.map((sentence) => {
              const isCurrent = sentence.id === currentSentenceId;
              const queued = queuedIds.has(sentence.id);
              return (
                <button
                  key={sentence.id}
                  type="button"
                  onClick={() => onJump(sentence.id)}
                  aria-current={isCurrent ? "true" : undefined}
                  // 原型 .peek-row：两端对齐、下边线、无 hover 底色；当前句仅中文变朱红。
                  className="flex w-full cursor-pointer justify-between gap-4 border-b border-rule bg-transparent p-0 py-3 text-left font-sans text-ink"
                >
                  <span className={cn("text-[14px] leading-[1.5]", isCurrent && "text-vermilion")}>
                    {sentence.zh}
                  </span>
                  <span className="shrink-0 text-[11px] tracking-[0.04em] text-ink-muted opacity-75">
                    {/* 原型 peek-row：级别 · 状态连排；状态口径是「是否在复习队列」
                        而非「是否练过」——引擎错才入队，全对首练不建卡。 */}
                    {[
                      sentence.level ? (LEVEL_LABELS[sentence.level] ?? sentence.level) : null,
                      isCurrent ? "当前" : queued ? "在队列" : "未入队",
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </button>
              );
            })
          )}
        </div>
      </aside>
    </div>
  );
}
