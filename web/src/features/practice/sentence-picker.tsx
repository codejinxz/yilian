import { useInfiniteQuery } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { Button } from "@/components/ui/button";
import { getPickerSentences } from "@/lib/api/content";
import { ApiError } from "@/lib/api-error";
import { describeDue } from "@/lib/format";
import { queryKeys } from "@/lib/query";
import type { PickerSentence } from "@/lib/schemas/content";
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

/**
 * 生命周期徽标：PUBLISHED 是选题面板的常态，不打徽标；其余状态照实展示，
 * 行不可点选（练习需要已发布句；点选后的内容也由 next?sentenceId= 决定）。
 */
const STATUS_LABELS: Partial<Record<PickerSentence["status"], string>> = {
  DRAFT: "草稿",
  DISABLED: "已停用",
  RECYCLED: "回收站",
  ARCHIVED: "已归档",
};

/** 练过态标签：null（无卡/匿名）与 NEW 同义「未学」——引擎错才入队、重置即 NEW。 */
const PRACTICE_STATE_LABELS: Record<PickerSentence["reviewState"] & string, string> = {
  NEW: "未学",
  LEARNING: "学习中",
  MASTERED: "已掌握",
};

/** 每页条数；与服务端 size 上限一致，大题库经「加载更多」翻页。 */
const PICKER_PAGE_SIZE = 100;

/** 深链失效的降级文案：bank 缺失或服务端 40401 都归于「题库不可用」，绝不挂在永久 pending。 */
const BANK_UNAVAILABLE_TEXT = "该题库不可用：链接指向的题库不存在或未发布。";

/**
 * 抽屉动效：原型 .drawer（右移入 24px、.26s、cubic-bezier(.2,.8,.2,1)）与
 * .drawer-mask（渐显 .2s ease）。styles.css 不可改，keyframes 随组件内联声明；
 * prefers-reduced-motion 下全局样式会把所有动画压到 0.001ms，无需单独降级。
 */
const DRAWER_STYLE = `
  .yilian-picker-panel { animation: yilian-picker-slide 0.26s cubic-bezier(0.2, 0.8, 0.2, 1) both; }
  .yilian-picker-mask { animation: yilian-picker-fade 0.2s ease both; }
  @keyframes yilian-picker-slide {
    from { transform: translateX(24px); opacity: 0; }
    to { transform: none; opacity: 1; }
  }
  @keyframes yilian-picker-fade {
    from { opacity: 0; }
    to { opacity: 1; }
  }
`;

/**
 * 把选题行组装成行尾 meta 段（级别 · 状态徽标 · 练过态），空段自动省略。
 * @param sentence 选题行
 * @param isCurrent 是否当前句
 * @returns 以「 · 」连接的展示段
 */
function pickerMeta(sentence: PickerSentence, isCurrent: boolean): string {
  return [
    sentence.level ? (LEVEL_LABELS[sentence.level] ?? sentence.level) : null,
    sentence.status === "PUBLISHED" || isCurrent ? null : (STATUS_LABELS[sentence.status] ?? null),
    isCurrent
      ? "当前"
      : sentence.reviewState === null || sentence.reviewState === "NEW"
        ? "未学"
        : sentence.reviewState === "LEARNING" && sentence.reviewDueAt
          ? `学习中 · ${describeDue(sentence.reviewDueAt)}`
          : (PRACTICE_STATE_LABELS[sentence.reviewState] ?? null),
  ]
    .filter(Boolean)
    .join(" · ");
}

/**
 * 练习选题面板：练习页内的展开层（非独立路由页），列出当前题库的句子，
 * 点选一句经 next?sentenceId= 指定练习（MANUAL）；Esc/遮罩/×关闭回到当前句。
 * 一屏一句纪律：面板不改变练习页本身，关闭后当前句与作答状态原样保留。
 *
 * <p>数据走 §5.3.1 选题句列表端点：匿名仅公开库（练过态恒「未学」），登录后本人私有库
 * 可见并逐行携带卡状态；无 bankId 时入口在练习页不渲染（onJump 也就不会被触发）。
 * 深链 bankId 失效（bank=null 或列表 40401）时按「该题库不可用」降级，不挂永久加载态。
 * 面板 portal 到 body 渲染：壳层 .page 的层叠上下文会让 fixed 层压不过顶栏。
 *
 * @param props.open 是否展开
 * @param props.bank 当前题库（id/名称/说明/句数）；null 时面板显示「题库不可用」降级态
 * @param props.currentSentenceId 当前练习的句子 id（行高亮 + aria-current）
 * @param props.onJump 点选已发布句的回调（跳句动作与状态清理由调用方执行）
 * @param props.onClose 关闭回调
 * @returns 展开层；open=false 时不渲染
 */
export function SentencePicker({
  open,
  bank,
  currentSentenceId,
  onJump,
  onClose,
}: {
  open: boolean;
  bank: { id: string; name: string; description: string | null; sentenceCount: number } | null;
  currentSentenceId: string | null;
  onJump: (sentenceId: string) => void;
  onClose: () => void;
}) {
  const panelRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    // Escape 关闭与原型 closeDrawer 一致；监听 document，面板内焦点位置无关紧要。
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    // 打开期间锁正文滚动，避免面板下的练习页跟着滚。
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    panelRef.current?.focus();
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [open, onClose]);

  const bankId = bank?.id ?? "";
  const pickerQuery = useInfiniteQuery({
    queryKey: queryKeys.pickerSentences(bankId),
    queryFn: ({ pageParam, signal }) =>
      getPickerSentences(bankId, { page: pageParam, size: PICKER_PAGE_SIZE }, signal),
    initialPageParam: 1,
    getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.page + 1 : undefined),
    enabled: open && bankId.length > 0,
    staleTime: 30_000,
  });
  const rows = pickerQuery.data?.pages.flatMap((page) => page.items) ?? [];

  if (!open) {
    return null;
  }

  // portal 到 body：壳层 .page 自带 z-index:1 的层叠上下文，面板写在页面内时
  // fixed 层永远压不过 z-index:10 的 .topbar——遮罩顶部条带与 × 按钮被顶栏盖住。
  // 脱离页面上下文后 z-[70] 直接与顶栏同层比较，覆盖全屏（confirm-dialog 同法）。
  return createPortal(
    <div
      className="fixed inset-0 z-[70] flex justify-end"
      role="dialog"
      aria-modal="true"
      aria-label={`题库 ${bank?.name ?? ""} 的句子列表`}
    >
      <style>{DRAWER_STYLE}</style>
      <button
        type="button"
        aria-label="关闭选题面板"
        onClick={onClose}
        className="yilian-picker-mask absolute inset-0 cursor-default border-none bg-ink/35 p-0"
      />
      <aside
        ref={panelRef}
        tabIndex={-1}
        // 尺寸与内边距逐值.drawer：440px、右缘线、padding 34px 32px 64px。
        className="yilian-picker-panel relative h-full w-[440px] max-w-[94vw] overflow-auto border-l border-rule bg-paper px-8 pb-16 pt-[34px] outline-none"
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
          题库 · {bank?.id ?? ""}
        </p>
        <h2 className="m-0 mb-[5px] font-serif text-[26px] font-medium leading-tight text-ink">
          {bank?.name ?? "题库"}
        </h2>
        {bank ? (
          <p className="m-0 font-mono text-[11px] tracking-[0.04em] text-ink-faint">
            {bank.sentenceCount} 句{bank.description ? ` · ${bank.description}` : ""}
          </p>
        ) : null}
        {/* 原型 .drawer .section-title：上边距 30px，全局类自带 16px 下边距。 */}
        <p className="section-title mt-[30px]">题目</p>
        <div>
          {!bank ? (
            // 深链带了已下架/他人私有库等无效 bankId 时，页面按「不可用」降级而不是
            // 永远停在加载态：查询对 bankId 为空恒禁用，pending 分支永远不会自愈。
            <p className="m-0 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
              {BANK_UNAVAILABLE_TEXT}
            </p>
          ) : pickerQuery.isPending ? (
            <p className="m-0 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
              正在载入句子…
            </p>
          ) : pickerQuery.isError ? (
            pickerQuery.error instanceof ApiError && pickerQuery.error.code === 40401 ? (
              // 列表请求期间题库被下架/删除（40401）：同归于「不可用」口径，不给重试误导。
              <p className="m-0 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
                {BANK_UNAVAILABLE_TEXT}
              </p>
            ) : (
              <p className="m-0 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
                句子列表加载失败，请稍后重试。
              </p>
            )
          ) : rows.length === 0 ? (
            <p className="m-0 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
              这个题库还没有句子。
            </p>
          ) : (
            rows.map((sentence) => {
              const isCurrent = sentence.id === currentSentenceId;
              const selectable = sentence.status === "PUBLISHED";
              return (
                <button
                  key={sentence.id}
                  type="button"
                  disabled={!selectable}
                  onClick={() => onJump(sentence.id)}
                  aria-current={isCurrent ? "true" : undefined}
                  // 原型 .peek-row：两端对齐、下边线、无 hover 底色；当前句仅中文变朱红。
                  // 非 PUBLISHED 行不可点选（练习需要已发布句），降透明度示意。
                  className={cn(
                    "flex w-full justify-between gap-4 border-b border-rule bg-transparent p-0 py-3 text-left font-sans text-ink",
                    selectable ? "cursor-pointer" : "cursor-not-allowed opacity-60",
                  )}
                >
                  <span className={cn("text-[14px] leading-[1.5]", isCurrent && "text-vermilion")}>
                    {sentence.zh}
                  </span>
                  <span className="shrink-0 text-[11px] tracking-[0.04em] text-ink-muted opacity-75">
                    {/* 原型 peek-row：级别 · 状态 · 练过态连排；练过态来自 §5.3.1 的
                        当前用户卡投影，匿名/未学展示「未学」。 */}
                    {pickerMeta(sentence, isCurrent)}
                  </span>
                </button>
              );
            })
          )}
          {pickerQuery.hasNextPage ? (
            <div className="pt-4 text-center">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={pickerQuery.isFetchingNextPage}
                onClick={() => void pickerQuery.fetchNextPage()}
              >
                {pickerQuery.isFetchingNextPage ? "正在载入…" : "加载更多"}
              </Button>
            </div>
          ) : null}
        </div>
      </aside>
    </div>,
    document.body,
  );
}
