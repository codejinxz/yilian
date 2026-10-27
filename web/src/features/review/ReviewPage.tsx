import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useSearch } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { Pagination } from "@/components/pagination";
import { getReviews } from "@/lib/api/practice";
import { shortId } from "@/lib/format";
import {
  getCachedSentences,
  getOverloadState,
  getReviewQueue,
  postponeOverdue,
} from "@/lib/local-engine";
import { isMockEnabled } from "@/lib/mock-flag";
import { queryKeys } from "@/lib/query";
import { cn } from "@/lib/utils";

type Filter = "all" | "due" | "learning" | "mastered";

/** 路由 search 可携带的筛选档：与 reviewRoute.validateSearch 的产出一致（不含「全部」）。 */
type RouteFilter = Exclude<Filter, "all">;

const DAY_MS = 86_400_000;
/** 客户端分页默认每页条数：真实模式的 useState 初值与 mock 的服务端 size 参数共用。 */
const PAGE_SIZE = 20;

const FILTERS: ReadonlyArray<{ value: Filter; label: string }> = [
  { value: "all", label: "全部" },
  { value: "due", label: "到期" },
  { value: "learning", label: "学习中" },
  { value: "mastered", label: "已掌握" },
];

/** 行视图模型：引擎队列与契约分页两种数据源归一到同一形状，渲染层不再感知来源。 */
interface ReviewRowVM {
  sentenceId: string;
  /** 中文句：mock 优先取契约投的 review.zh，join 不到再退引擎句缓存；都没有时展示题目短 id。 */
  zh: string | null;
  mode: "CLOZE" | "FREE";
  state: "NEW" | "LEARNING" | "MASTERED";
  dueAt: string;
  tags: string[];
}

/** 状态徽标的展示信息。 */
interface DueBadge {
  text: string;
  tone: "moss" | "vermilion" | "neutral";
}

/** 三档汇总；与引擎 getReviewQueue().summary 同构。 */
interface ReviewSummary {
  due: number;
  learning: number;
  mastered: number;
}

/**
 * 状态徽标文案（）：已掌握苔绿；学习中已到期「今天」朱红；明天与 N 天后中性。
 * NEW 卡（mock 演示数据里有）不是到期语义，即使 dueAt 已过也只按时间展示。
 * @param row 复习行
 * @returns 徽标文案与色调
 */
function dueBadgeFor(row: ReviewRowVM): DueBadge {
  if (row.state === "MASTERED") {
    return { text: "已掌握", tone: "moss" };
  }
  const diffMs = new Date(row.dueAt).getTime() - Date.now();
  if (diffMs <= 0) {
    return {
      text: "今天",
      tone: row.state === "LEARNING" ? "vermilion" : "neutral",
    };
  }
  const days = Math.ceil(diffMs / DAY_MS);
  return { text: days === 1 ? "明天" : `${days} 天后`, tone: "neutral" };
}

/**
 * 把筛选映射为契约分页参数（mock 模式用）；筛选变化时页码由页面重置为 1。
 * 「到期」沿用原型语义：学习中且已到期，state+due 组合参数恰好表达。
 * @param filter 页签
 * @param page 页码
 * @returns 查询参数
 */
function paramsFor(filter: Filter, page: number) {
  if (filter === "due") {
    return { page, size: PAGE_SIZE, state: "LEARNING", due: true } as const;
  }
  if (filter === "learning") {
    return { page, size: PAGE_SIZE, state: "LEARNING" } as const;
  }
  if (filter === "mastered") {
    return { page, size: PAGE_SIZE, state: "MASTERED" } as const;
  }
  return { page, size: PAGE_SIZE } as const;
}

/**
 * 底部轻提示：与今日页同一套原型 toast 样式；两页各自持有，避免跨 feature 依赖。
 * @param props message 当前文案、visible 是否可见
 */
function ReviewToast({ message, visible }: { message: string | null; visible: boolean }) {
  return (
    <div
      aria-live="polite"
      className={cn(
        "pointer-events-none fixed inset-x-0 bottom-8 z-50 flex justify-center transition-all duration-200",
        visible && message ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0",
      )}
    >
      {message ? (
        <p className="rounded-[2px] bg-ink px-4 py-[9px] text-[12.5px] leading-5 text-paper">
          {message}
        </p>
      ) : null}
    </div>
  );
}

/**
 * 复习页：view-review——降载条、三档汇总、四页签与中文句行列表。
 *
 * 数据源分两种模式：真实模式直接读本地引擎 getReviewQueue（行自带中文句与考点，本地引擎能力），
 * 客户端按页签过滤并分页；mock 模式沿用 getReviews 契约分页，中文句用引擎题库缓存兜底 join。
 * 降载条与顺延是引擎能力（getOverloadState / postponeOverdue），mock 引擎无复习数据，天然不会误触发。
 */
export function ReviewPage() {
  const queryClient = useQueryClient();
  const mock = isMockEnabled();
  /**
   * filter 只取路由 search 的初值：练习页「{n} 句到期」chip 以 ?filter=due 直达对应档（原型 dueChip）。
   * 仅在 useState 初始化器里读一次——此后用户切换页签以页签状态为准，不回写也不跟随 URL；
   * validateSearch 已保证只有三个合法档，这里再收窄一次兜底，非法或缺省一律落「全部」。
   */
  const search = useSearch({ strict: false }) as { filter?: RouteFilter };
  const [filter, setFilter] = useState<Filter>(() =>
    search.filter === "due" || search.filter === "learning" || search.filter === "mastered"
      ? search.filter
      : "all",
  );
  const [page, setPage] = useState(1);
  // 真实模式客户端分页的每页条数，配合共享分页器的尺寸档；mock 固定按 PAGE_SIZE 走服务端参数。
  const [pageSize, setPageSize] = useState(PAGE_SIZE);
  /** 「关闭降载」只在当前会话内收起，刷新或离开页面后重新按真实状态判断。 */
  const [overloadDismissed, setOverloadDismissed] = useState(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [toastVisible, setToastVisible] = useState(false);
  const toastTimer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (toastTimer.current !== null) {
        window.clearTimeout(toastTimer.current);
      }
    },
    [],
  );

  const showToast = (text: string) => {
    setToastMessage(text);
    setToastVisible(true);
    if (toastTimer.current !== null) {
      window.clearTimeout(toastTimer.current);
    }
    toastTimer.current = window.setTimeout(() => setToastVisible(false), 1800);
  };

  /** 真实模式：引擎复习队列全量（按 dueAt 升序，行带中文句）。 */
  const engineQueueQuery = useQuery({
    queryKey: ["review", "engine-queue"],
    queryFn: () => getReviewQueue(),
    enabled: !mock,
  });

  /** mock 模式：契约分页列表。 */
  const params = paramsFor(filter, page);
  const mockPageQuery = useQuery({
    queryKey: queryKeys.reviews(params),
    queryFn: ({ signal }) => getReviews(params, signal),
    enabled: mock,
  });

  /** mock 模式：三档汇总用三个 size=1 的 total 拼，不拉明细。 */
  const mockSummaryQuery = useQuery({
    queryKey: ["review", "mock-summary"],
    queryFn: async (): Promise<ReviewSummary> => {
      const [due, learning, mastered] = await Promise.all([
        getReviews({ page: 1, size: 1, state: "LEARNING", due: true }),
        getReviews({ page: 1, size: 1, state: "LEARNING" }),
        getReviews({ page: 1, size: 1, state: "MASTERED" }),
      ]);
      return { due: due.total, learning: learning.total, mastered: mastered.total };
    },
    enabled: mock,
  });

  /** mock 模式：复习卡契约不含中文句，用引擎题库缓存 join；缓存为空时行退回短 id 展示。 */
  const joinQuery = useQuery({
    queryKey: ["review", "sentence-join"],
    queryFn: () => getCachedSentences(),
    enabled: mock,
  });
  const sentenceById = new Map((joinQuery.data ?? []).map((sentence) => [sentence.id, sentence]));

  /** 降载状态；引擎无数据（mock 模式）时 active=false，降载条不显示，不造假数据。 */
  const overloadQuery = useQuery({
    queryKey: ["review", "overload"],
    queryFn: () => getOverloadState(),
  });

  const postponeMutation = useMutation({
    mutationFn: () => postponeOverdue(3),
    onSuccess: (count) => {
      showToast(`已帮你减负 ${count} 句，顺延 3 天`);
      void queryClient.invalidateQueries({ queryKey: ["review"] });
      void queryClient.invalidateQueries({ queryKey: queryKeys.today });
    },
    onError: (error) => {
      showToast(error instanceof Error ? `顺延失败：${error.message}` : "顺延失败，请稍后重试");
    },
  });

  // —— 两种模式归一到同一组行与分页信息 ——

  const engineRows: ReviewRowVM[] = (engineQueueQuery.data?.rows ?? []).map(
    ({ sentence, review }) => ({
      sentenceId: review.sentenceId,
      zh: sentence.zh,
      // 引擎判分以 blank 是否为空选择填空/整句，行模式据此还原，与排程语义一致。
      mode: sentence.blank.length > 0 ? "CLOZE" : "FREE",
      state: review.state,
      dueAt: review.dueAt,
      tags: sentence.tags,
    }),
  );

  const mockRows: ReviewRowVM[] = (mockPageQuery.data?.items ?? []).map((item) => ({
    sentenceId: item.sentenceId,
    // 契约已在复习卡上投影中文句（mock），兜底再 join 一次引擎句缓存，修复裸 ID 展示。
    zh: item.zh ?? sentenceById.get(item.sentenceId)?.zh ?? null,
    mode: item.mode,
    state: item.state,
    dueAt: item.dueAt,
    tags: sentenceById.get(item.sentenceId)?.tags ?? [],
  }));

  const summary: ReviewSummary = mock
    ? (mockSummaryQuery.data ?? { due: 0, learning: 0, mastered: 0 })
    : (engineQueueQuery.data?.summary ?? { due: 0, learning: 0, mastered: 0 });

  const listPending = mock ? mockPageQuery.isPending : engineQueueQuery.isPending;
  const listError = mock ? mockPageQuery.error : engineQueueQuery.error;

  const allRows = mock ? mockRows : engineRows;
  const nowMs = Date.now();
  const filteredRows = allRows.filter((row) => {
    switch (filter) {
      case "due":
        return row.state === "LEARNING" && new Date(row.dueAt).getTime() <= nowMs;
      case "learning":
        return row.state === "LEARNING";
      case "mastered":
        return row.state === "MASTERED";
      default:
        return true;
    }
  });

  // mock 走服务端分页；真实模式客户端分页，二者对渲染层同构。
  // 页码越界（筛选变少后 page 停在旧值）时回落最后一页，与 PageResponse 语义一致。
  const overload = overloadQuery.data;
  const overloadActive = (overload?.active ?? false) && !overloadDismissed;
  // 降载激活时只推 5 句最紧急的（原型语义），顺延或关闭降载后恢复完整列表。
  const visibleRows = overloadActive ? filteredRows.slice(0, 5) : filteredRows;
  const realTotalPages = Math.max(1, Math.ceil(visibleRows.length / pageSize));
  const safePage = Math.min(page, realTotalPages);
  const listRows = mock
    ? visibleRows
    : visibleRows.slice((safePage - 1) * pageSize, safePage * pageSize);
  const pageInfo = mock
    ? {
        page: mockPageQuery.data?.page ?? 1,
        total: mockPageQuery.data?.total ?? 0,
        totalPages: mockPageQuery.data?.totalPages ?? 1,
        hasNext: mockPageQuery.data?.hasNext ?? false,
      }
    : {
        page: safePage,
        total: filteredRows.length,
        totalPages: realTotalPages,
        hasNext: safePage < realTotalPages,
      };

  return (
    <div>
      <header>
        <h1 className="page-title">复习</h1>
        <p className="page-sub">按到期时间排列。答错的句子会自动进来，连续两次全对就归档。</p>
      </header>

      {overload && overloadActive ? (
        <div className="mb-5 flex flex-wrap items-center justify-between gap-4 border border-vermilion/35 px-4 py-3.5 text-xs text-ink-muted">
          <div className="min-w-0">
            <p className="font-medium text-ink">到期 {overload.dueCount} 句，已进入降载模式</p>
            <p className="mt-[5px] font-mono text-[10px] tracking-[0.1em] text-ink-faint">
              只推 5 句最紧急的，其余可以顺延 3 天。
            </p>
          </div>
          <div className="flex shrink-0 gap-3.5">
            <button
              type="button"
              className="linkish"
              disabled={postponeMutation.isPending}
              onClick={() => postponeMutation.mutate()}
            >
              顺延其余
            </button>
            <button type="button" className="linkish" onClick={() => setOverloadDismissed(true)}>
              关闭降载
            </button>
          </div>
        </div>
      ) : null}

      <div className="flex flex-wrap gap-x-12 gap-y-4 border-y border-rule py-6 max-[760px]:gap-x-[26px]">
        <div>
          <b className="block font-serif text-[28px] font-medium leading-none text-ink">
            {summary.due}
          </b>
          <span className="mt-[9px] block font-mono text-[10px] tracking-[0.14em] text-ink-faint">
            今日到期
          </span>
        </div>
        <div>
          <b className="block font-serif text-[28px] font-medium leading-none text-ink">
            {summary.learning}
          </b>
          <span className="mt-[9px] block font-mono text-[10px] tracking-[0.14em] text-ink-faint">
            学习中
          </span>
        </div>
        <div>
          <b className="block font-serif text-[28px] font-medium leading-none text-ink">
            {summary.mastered}
          </b>
          <span className="mt-[9px] block font-mono text-[10px] tracking-[0.14em] text-ink-faint">
            已掌握
          </span>
        </div>
      </div>

      {/* 原型 .filters：无边框容器、12px 页签、仅下边线指示选中（与 FilterTabs 的整行分隔线形态不同）。 */}
      {/* biome-ignore lint/a11y/useSemanticElements: role=group 表达纯筛选分组语义；fieldset 自带默认样式与布局差异，会破坏稿纸式排版。 */}
      <div
        role="group"
        aria-label="复习筛选"
        className="mt-[30px] mb-[6px] flex flex-wrap gap-x-5 gap-y-2"
      >
        {FILTERS.map((item) => {
          const active = item.value === filter;
          return (
            <button
              key={item.value}
              type="button"
              aria-pressed={active}
              onClick={() => {
                setFilter(item.value);
                setPage(1);
              }}
              className={cn(
                "border-b pb-[3px] text-xs tracking-[0.05em] transition-colors",
                active ? "border-vermilion text-ink" : "border-transparent text-ink-faint",
              )}
            >
              {item.label}
            </button>
          );
        })}
      </div>

      {listPending ? (
        <LoadingBlock label="正在读取复习队列…" rows={5} />
      ) : listError ? (
        <ErrorState
          className="mt-6"
          error={listError}
          onRetry={() => {
            if (mock) {
              void mockPageQuery.refetch();
            } else {
              void engineQueueQuery.refetch();
            }
          }}
        />
      ) : listRows.length === 0 ? (
        <p className="py-10 text-center text-xs text-ink-faint">
          这里还是空的。去练几句，答错的会自动进来。
        </p>
      ) : (
        <>
          <ul>
            {listRows.map((row) => {
              const badge = dueBadgeFor(row);
              return (
                <li
                  key={row.sentenceId}
                  className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3.5 gap-y-2.5 border-b border-rule py-[18px] min-[761px]:grid-cols-[minmax(0,1fr)_auto_auto] min-[761px]:gap-5"
                >
                  <div className="min-w-0">
                    <p className="truncate font-serif text-base leading-[1.5] text-ink">
                      {row.zh ?? `题目 ${shortId(row.sentenceId)}`}
                    </p>
                    <p className="mt-[7px] font-mono text-[10px] tracking-[0.1em] text-ink-faint">
                      {row.mode === "CLOZE" ? "填空" : "整句"}
                      {row.tags.length > 0 ? ` · ${row.tags.join(" / ")}` : ""}
                    </p>
                  </div>

                  <span
                    className={cn(
                      "justify-self-end whitespace-nowrap rounded-[2px] border px-2 py-1 font-mono text-[10px] tracking-[0.1em]",
                      badge.tone === "moss"
                        ? "border-moss/40 text-moss"
                        : badge.tone === "vermilion"
                          ? "border-vermilion/40 text-vermilion"
                          : "border-rule text-ink-faint",
                    )}
                  >
                    {badge.text}
                  </span>

                  <div className="flex items-center gap-3 max-[760px]:col-start-2">
                    <Link
                      to="/practice"
                      search={{ sentenceId: row.sentenceId, mode: row.mode }}
                      className="whitespace-nowrap text-xs tracking-[0.05em] text-ink-muted transition-colors duration-200 hover:text-vermilion"
                    >
                      {row.state === "MASTERED" ? "重练" : "开始"}
                    </Link>
                  </div>
                </li>
              );
            })}
          </ul>

          {/* 原型 .pager 形态的共享分页器：mock 走服务端分页（不给尺寸档，size 恒为 20），
              真实模式客户端分页，每页条数可由尺寸档调整。 */}
          <Pagination
            page={pageInfo.page}
            size={mock ? PAGE_SIZE : pageSize}
            total={pageInfo.total}
            totalPages={pageInfo.totalPages}
            hasNext={pageInfo.hasNext}
            onPageChange={setPage}
            onSizeChange={
              mock
                ? undefined
                : (nextSize) => {
                    setPageSize(nextSize);
                    setPage(1);
                  }
            }
          />
        </>
      )}

      <ReviewToast message={toastMessage} visible={toastVisible} />
    </div>
  );
}
