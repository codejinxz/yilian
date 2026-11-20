import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { Pagination } from "@/components/pagination";
import { isGuestSession } from "@/lib/api";
import { getReviewOverload, getReviews, postponeReviews } from "@/lib/api/practice";
import {
  buildDueCalendar,
  DUE_CALENDAR_DAYS,
  type DueBucket,
  type DueCalendar,
} from "@/lib/due-calendar";
import { shortId } from "@/lib/format";
import {
  ensureCachedSentences,
  getCachedSentences,
  getOverloadState,
  getReviewQueue,
  postponeOverdue,
} from "@/lib/local-engine";
import { isMockEnabled } from "@/lib/mock-flag";
import { meQueryOptions, queryKeys } from "@/lib/query";
import { cn } from "@/lib/utils";

type Filter = "all" | "due" | "learning" | "mastered";

/** 路由 search 可携带的筛选档：与 reviewRoute.validateSearch 的产出一致（不含「全部」）。 */
type RouteFilter = Exclude<Filter, "all">;

const DAY_MS = 86_400_000;
/** 客户端分页默认每页条数：真实模式的 useState 初值与 mock 的服务端 size 参数共用。 */
const PAGE_SIZE = 20;

/** 「提前复习」提示后延迟跳转的时长：让提示可被扫一眼再进题，离开页面即取消。 */
const EARLY_REVIEW_NAV_DELAY_MS = 1000;

/** 周几短标签：第 3 桶起按桶位日期的周几展示。 */
const WEEKDAY_LABELS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

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

/** 降载状态视图：mock/游客读引擎（active/dueCount），登录态读契约（overload/dueCount/pushLimit）。 */
interface OverloadVM {
  active: boolean;
  dueCount: number;
  pushLimit: number;
}

/** 顺延结果视图：postponed 为顺延张数，days 为顺延天数（两端统一 3 天）。 */
interface PostponeVM {
  postponed: number;
  days: number;
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
 * 桶位标签：前两桶单独命名，其余按桶位日期的周几。
 * @param index 桶位序号（0 为今天）
 * @param bucket 到期桶
 * @returns 可直接展示的短标签
 */
function bucketLabel(index: number, bucket: DueBucket): string {
  if (index === 0) {
    return "今天";
  }
  if (index === 1) {
    return "明天";
  }
  return WEEKDAY_LABELS[new Date(`${bucket.date}T00:00:00`).getDay()];
}

/**
 * 7 日到期日历区段：今天..+6 天七个日界桶的到期计数，今日桶以描边与朱红数字可辨。
 * 数据未就绪或读取失败时按桶位渲染占位，不给假数字；统计被单页截断时注明统计上限。
 * @param props calendar 日历视图（null 表示尚未取得数据）
 * @param props pending 数据是否仍在读取
 * @param props errored 数据读取是否失败
 * @param props limitHint 分页截断时的上限提示（null 表示未截断）
 */
function ReviewDueCalendar({
  calendar,
  pending,
  errored,
  limitHint,
}: {
  calendar: DueCalendar | null;
  pending: boolean;
  errored: boolean;
  limitHint: string | null;
}) {
  // 数据未就绪时整段隐藏：会话恢复与查询进行中不给假数字，也不让 0 计数先落屏；
  // 引擎快照未携带日历字段（null 且已就绪）时才退到占位桶。
  if (calendar === null && pending) {
    return null;
  }
  return (
    <section aria-label="未来 7 天到期" className="mt-[26px] border-b border-rule pb-5">
      <p className="font-mono text-[10px] tracking-[0.14em] text-ink-faint">未来 7 天到期</p>
      {calendar ? (
        <ol className="mt-3 grid grid-cols-7 gap-x-1.5">
          {calendar.buckets.map((bucket, index) => (
            <li
              key={bucket.date}
              aria-current={bucket.isToday ? "date" : undefined}
              className={cn(
                "flex flex-col items-center gap-[7px] rounded-[4px] border py-[10px]",
                bucket.isToday ? "border-vermilion/35 bg-vermilion/[0.04]" : "border-transparent",
              )}
            >
              <b
                className={cn(
                  "font-serif text-[19px] font-medium leading-none",
                  bucket.isToday && bucket.count > 0 ? "text-vermilion" : "text-ink",
                )}
              >
                {bucket.count}
              </b>
              <span className="font-mono text-[10px] tracking-[0.08em] text-ink-faint">
                {bucketLabel(index, bucket)}
              </span>
            </li>
          ))}
        </ol>
      ) : (
        <ol className="mt-3 grid grid-cols-7 gap-x-1.5">
          {Array.from({ length: DUE_CALENDAR_DAYS }, (_, index) => (
            <li
              // biome-ignore lint/suspicious/noArrayIndexKey: 占位桶没有业务标识，7 个槽位由固定算法生成且顺序稳定，index 即稳定标识。
              key={`placeholder-${index}`}
              className="flex flex-col items-center py-[10px] font-serif text-[19px] leading-none text-ink-faint"
            >
              …
            </li>
          ))}
        </ol>
      )}
      {limitHint ? (
        <p className="mt-[10px] font-mono text-[10px] tracking-[0.06em] text-ink-faint">
          {limitHint}
        </p>
      ) : null}
      {errored && !calendar ? (
        <p className="mt-[10px] text-xs text-ink-muted">到期日历暂时读不到，稍后会自动重试。</p>
      ) : null}
    </section>
  );
}

/**
 * 复习页：view-review——降载条、三档汇总、四页签与中文句行列表。
 *
 * 数据源按登录态分流：游客直连本地引擎 getReviewQueue（行自带中文句与考点，
 * 客户端按页签过滤并分页）；登录用户走契约接口——getReviews 契约分页 + 三档汇总（三个
 * size=1 的 total）+ 降载/顺延走 §3.6 的 overload/postpone 端点，中文句用题库缓存 join
 * （ensureCachedSentences，匿名可拉）；mock 模式沿用 getReviews 契约分页与引擎降载能力
 * （mock 引擎无复习数据，降载条与顺延天然不会误触发）。
 *
 * 冷启动会话恢复期间（me 未确认、探针未确认登录）整页数据区渲染加载态而非游客空态：
 * 本地引擎会瞬时回空队列，先渲染「这里还是空的」与全零计数、会话确认后再跳真实数据的
 * 闪变（冷启动竞态）由此而来。
 */
export function ReviewPage() {
  const queryClient = useQueryClient();
  const mock = isMockEnabled();
  // 游客与 mock 互斥（mock 优先级最高）；登录态分支 = 真实模式且非游客（overload/postpone
  // 与 join 的 queryFn 内按 mock || guest 走引擎、否则走契约端点）。
  const guest = !mock && isGuestSession();
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
  // 游客客户端分页的每页条数，配合共享分页器的尺寸档；mock/登录态固定按 PAGE_SIZE 走服务端参数。
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

  const navigate = useNavigate();
  /** 「提前复习」提示后的延迟跳转计时器；卸载时取消，避免用户已改道仍被拉去练习。 */
  const earlyNavTimer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (earlyNavTimer.current !== null) {
        window.clearTimeout(earlyNavTimer.current);
      }
    },
    [],
  );

  /**
   * 从复习列表进练习：来源卡是整句就按整句进（复习不强制变填空），填空同理。
   * 未到期的学习中条目先 toast「提前复习」再进题——与 FSRS 语义一致，练了也会按排程再排；
   * 已掌握条目是「重练」，不属调度语义，直接进。
   * @param row 复习行
   */
  function startReview(row: ReviewRowVM) {
    const search = { sentenceId: row.sentenceId, mode: row.mode };
    if (row.state === "LEARNING" && new Date(row.dueAt).getTime() > Date.now()) {
      showToast("提前复习：这条还没到最佳时间，练了也会再排");
      if (earlyNavTimer.current !== null) {
        window.clearTimeout(earlyNavTimer.current);
      }
      earlyNavTimer.current = window.setTimeout(() => {
        earlyNavTimer.current = null;
        void navigate({ to: "/practice", search });
      }, EARLY_REVIEW_NAV_DELAY_MS);
      return;
    }
    void navigate({ to: "/practice", search });
  }

  /** 游客：引擎复习队列全量（按 dueAt 升序，行带中文句），客户端过滤与分页。 */
  const engineQueueQuery = useQuery({
    queryKey: ["review", "engine-queue"],
    queryFn: () => getReviewQueue(),
    enabled: guest,
  });

  /** mock 与登录态：契约分页列表（mock 由 handlers 应答，登录态走服务端）。 */
  const params = paramsFor(filter, page);
  const contractPageQuery = useQuery({
    queryKey: queryKeys.reviews(params),
    queryFn: ({ signal }) => getReviews(params, signal),
    enabled: !guest,
  });

  /** mock 与登录态：三档汇总用三个 size=1 的 total 拼，不拉明细。 */
  const summaryQuery = useQuery({
    queryKey: ["review", "summary", mock],
    queryFn: async (): Promise<ReviewSummary> => {
      const [due, learning, mastered] = await Promise.all([
        getReviews({ page: 1, size: 1, state: "LEARNING", due: true }),
        getReviews({ page: 1, size: 1, state: "LEARNING" }),
        getReviews({ page: 1, size: 1, state: "MASTERED" }),
      ]);
      return { due: due.total, learning: learning.total, mastered: mastered.total };
    },
    enabled: !guest,
  });

  /** 7 日日历与列表/汇总共用既有 /reviews 分页端点：登录态取学习中首页（dueAt 升序）
      客户端按本地日界分桶，游客侧由引擎快照直出，组件不感知来源差异。 */
  const calendarQuery = useQuery({
    queryKey: queryKeys.reviews({ page: 1, size: PAGE_SIZE, state: "LEARNING" }),
    queryFn: ({ signal }) => getReviews({ page: 1, size: PAGE_SIZE, state: "LEARNING" }, signal),
    enabled: !guest,
  });

  /** mock 与登录态：复习卡契约不含中文句，用题库缓存 join；缓存为空时行退回短 id 展示。
      mock 保持只读本地缓存（不触发真实网络刷新），登录态缓存为空时联网重建一次。 */
  const joinQuery = useQuery({
    queryKey: ["review", "sentence-join", mock],
    queryFn: () => (mock ? getCachedSentences() : ensureCachedSentences()),
    enabled: !guest,
  });
  const sentenceById = new Map((joinQuery.data ?? []).map((sentence) => [sentence.id, sentence]));

  /** 降载状态：mock/游客读引擎（getOverloadState），登录态走契约端点（getReviewOverload）。 */
  const overloadQuery = useQuery({
    queryKey: ["review", "overload", mock, guest],
    queryFn: async (): Promise<OverloadVM> => {
      if (mock || guest) {
        const engineState = await getOverloadState();
        return { active: engineState.active, dueCount: engineState.dueCount, pushLimit: 5 };
      }
      const view = await getReviewOverload();
      return { active: view.overload, dueCount: view.dueCount, pushLimit: view.pushLimit };
    },
  });

  /** 顺延：本地引擎 golden 基准 3 天（原型口径），服务端已对齐 POSTPONE_DAYS=3。 */
  const postponeMutation = useMutation({
    mutationFn: async (): Promise<PostponeVM> => {
      if (mock || guest) {
        return { postponed: await postponeOverdue(3), days: 3 };
      }
      const view = await postponeReviews();
      return { postponed: view.postponed, days: 3 };
    },
    onSuccess: ({ postponed, days }) => {
      showToast(`已帮你减负 ${postponed} 句，顺延 ${days} 天`);
      void queryClient.invalidateQueries({ queryKey: ["review"] });
      void queryClient.invalidateQueries({ queryKey: queryKeys.today });
    },
    onError: (error) => {
      showToast(error instanceof Error ? `顺延失败：${error.message}` : "顺延失败，请稍后重试");
    },
  });

  // —— 三种模式归一到同一组行与分页信息 ——

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

  const contractRows: ReviewRowVM[] = (contractPageQuery.data?.items ?? []).map((item) => ({
    sentenceId: item.sentenceId,
    // mock 在复习卡上投影中文句；服务端不投影，兜底 join 题库缓存，修复裸 ID 展示。
    zh: item.zh ?? sentenceById.get(item.sentenceId)?.zh ?? null,
    mode: item.mode,
    state: item.state,
    dueAt: item.dueAt,
    tags: sentenceById.get(item.sentenceId)?.tags ?? [],
  }));

  const summary: ReviewSummary = guest
    ? (engineQueueQuery.data?.summary ?? { due: 0, learning: 0, mastered: 0 })
    : (summaryQuery.data ?? { due: 0, learning: 0, mastered: 0 });

  // 冷启动会话恢复竞态：me 未确认前 isGuestSession 暂按游客语义，
  // 本地引擎瞬时回空队列会把「这里还是空的」空态与全零计数先渲染出来，会话确认后才跳真实数据。
  // 会话恢复期间（mock 无此竞态）数据区按加载态渲染，空态与零计数都不落屏。
  const me = useQuery(meQueryOptions);
  const sessionRestoring = !mock && me.isPending;
  const listPending =
    sessionRestoring || (guest ? engineQueueQuery.isPending : contractPageQuery.isPending);
  const listError = guest ? engineQueueQuery.error : contractPageQuery.error;

  /** 7 日日历视图：游客读引擎快照自带日历；mock/登录态用契约首页条目按本地日界分桶。 */
  const dueCalendar = guest
    ? (engineQueueQuery.data?.dueCalendar ?? null)
    : calendarQuery.data
      ? buildDueCalendar(
          calendarQuery.data.items.map((item) => item.dueAt),
          new Date(),
          !calendarQuery.data.hasNext,
        )
      : null;
  const calendarPending =
    sessionRestoring || (guest ? engineQueueQuery.isPending : calendarQuery.isPending);
  const calendarErrored = guest ? engineQueueQuery.isError : calendarQuery.isError;
  // 游客引擎全量分桶无截断；契约侧被单页截断时注明统计上限，不假装全量。
  const calendarLimitHint =
    guest || !calendarQuery.data?.hasNext
      ? null
      : `到期较多，日历只统计最近取回的 ${PAGE_SIZE} 句。`;

  const allRows = guest ? engineRows : contractRows;
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

  // mock/登录态走服务端分页；游客客户端分页，二者对渲染层同构。
  // 页码越界（筛选变少后 page 停在旧值）时回落最后一页，与 PageResponse 语义一致。
  const overload = overloadQuery.data;
  const overloadActive = (overload?.active ?? false) && !overloadDismissed;
  // 降载激活时只推最紧急的 pushLimit 句（原型语义），顺延或关闭降载后恢复完整列表。
  const visibleRows = overloadActive
    ? filteredRows.slice(0, overload?.pushLimit ?? 5)
    : filteredRows;
  const realTotalPages = Math.max(1, Math.ceil(visibleRows.length / pageSize));
  const safePage = Math.min(page, realTotalPages);
  const listRows = guest
    ? visibleRows.slice((safePage - 1) * pageSize, safePage * pageSize)
    : visibleRows;
  const pageInfo = guest
    ? {
        page: safePage,
        total: filteredRows.length,
        totalPages: realTotalPages,
        hasNext: safePage < realTotalPages,
      }
    : {
        page: contractPageQuery.data?.page ?? 1,
        total: contractPageQuery.data?.total ?? 0,
        totalPages: contractPageQuery.data?.totalPages ?? 1,
        hasNext: contractPageQuery.data?.hasNext ?? false,
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
              只推 {overload.pushLimit} 句最紧急的，其余可以顺延 3 天。
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
            {sessionRestoring ? "…" : summary.due}
          </b>
          <span className="mt-[9px] block font-mono text-[10px] tracking-[0.14em] text-ink-faint">
            今日到期
          </span>
        </div>
        <div>
          <b className="block font-serif text-[28px] font-medium leading-none text-ink">
            {sessionRestoring ? "…" : summary.learning}
          </b>
          <span className="mt-[9px] block font-mono text-[10px] tracking-[0.14em] text-ink-faint">
            学习中
          </span>
        </div>
        <div>
          <b className="block font-serif text-[28px] font-medium leading-none text-ink">
            {sessionRestoring ? "…" : summary.mastered}
          </b>
          <span className="mt-[9px] block font-mono text-[10px] tracking-[0.14em] text-ink-faint">
            已掌握
          </span>
        </div>
      </div>

      <ReviewDueCalendar
        calendar={dueCalendar}
        pending={calendarPending}
        errored={calendarErrored}
        limitHint={calendarLimitHint}
      />

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
            if (guest) {
              void engineQueueQuery.refetch();
            } else {
              void contractPageQuery.refetch();
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
                    <button
                      type="button"
                      onClick={() => startReview(row)}
                      className="whitespace-nowrap text-xs tracking-[0.05em] text-ink-muted transition-colors duration-200 hover:text-vermilion"
                    >
                      {row.state === "MASTERED" ? "重练" : "开始"}
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>

          {/* 原型 .pager 形态的共享分页器：mock/登录态走服务端分页（不给尺寸档，size 恒为 20），
              游客客户端分页，每页条数可由尺寸档调整。 */}
          <Pagination
            page={pageInfo.page}
            size={guest ? pageSize : PAGE_SIZE}
            total={pageInfo.total}
            totalPages={pageInfo.totalPages}
            hasNext={pageInfo.hasNext}
            onPageChange={setPage}
            onSizeChange={
              guest
                ? (nextSize) => {
                    setPageSize(nextSize);
                    setPage(1);
                  }
                : undefined
            }
          />
        </>
      )}

      <ReviewToast message={toastMessage} visible={toastVisible} />
    </div>
  );
}
