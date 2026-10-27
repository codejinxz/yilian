import { useQuery } from "@tanstack/react-query";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { getAccuracy, getErrorTags } from "@/lib/api/analytics";
import { localDateString } from "@/lib/format";
import { getStatsSnapshot } from "@/lib/local-engine";
import { isMockEnabled } from "@/lib/mock-flag";
import { queryKeys } from "@/lib/query";
import { cn } from "@/lib/utils";

const DAY_MS = 86_400_000;

/** mock 演示统计的形状；与 mocks/handlers 的 getMockStatsDemo 返回值保持一致。 */
interface StatsDemo {
  dailyTrend: Array<{ date: string; attempts: number; correctRate: number }>;
  recent: Array<{
    attemptId: string;
    sentenceId: string;
    zh: string;
    mode: "CLOZE" | "FREE";
    kind: "FIRST" | "RETRY" | "REVEAL";
    score: number | null;
    createdAt: string;
  }>;
}

/** 统计页视图模型：引擎快照与 mock 三源归一到同一形状，渲染层不再感知来源。 */
interface StatsView {
  streakDays: number;
  totalAttempts: number;
  averageScore: number;
  /** 旧 → 新 14 天作答数（key 为本地日期），今天在最后。 */
  daily14: Array<{ key: string; count: number }>;
  /** 考点错误 Top5，按次数降序。 */
  errorTags: Array<{ tag: string; count: number }>;
  recent: Array<{
    /** 仅作列表 key 用：真实模式引擎快照不带 attemptId；mock 演示数据带。 */
    attemptId: string | null;
    zh: string | null;
    mode: "CLOZE" | "FREE";
    score: number | null;
    createdAt: string;
  }>;
}

/**
 * 相对今天的日期描述；fmtWhen 的今天/昨天/N 天前，但按本地日界取整，
 * 避免「昨晚 23 点、今早 1 点」被小时差压成同一天。
 * @param iso ISO 时间
 * @returns 可直接展示的相对日期
 */
function relativeDay(iso: string): string {
  const time = new Date(iso).getTime();
  if (Number.isNaN(time)) {
    return "—";
  }
  const dayStart = (value: number) => {
    const date = new Date(value);
    date.setHours(0, 0, 0, 0);
    return date.getTime();
  };
  const diff = Math.round((dayStart(Date.now()) - dayStart(time)) / DAY_MS);
  if (diff <= 0) {
    return "今天";
  }
  return diff === 1 ? "昨天" : `${diff} 天前`;
}

/**
 * 引擎统计快照 → 视图模型（本地引擎能力）。
 * 引擎 recent 不含 attemptId（快照投影的最小字段集），因此真实模式最近记录没有回跳链接。
 * @param snapshot 引擎快照
 * @returns 视图模型
 */
function viewFromEngine(snapshot: Awaited<ReturnType<typeof getStatsSnapshot>>): StatsView {
  return {
    streakDays: snapshot.streakDays,
    totalAttempts: snapshot.totalAttempts,
    averageScore: snapshot.averageScore,
    daily14: snapshot.daily14.map((count, index) => ({
      key: localDateString(new Date(Date.now() - (13 - index) * DAY_MS)),
      count,
    })),
    errorTags: [...snapshot.errorTags].sort((a, b) => b.count - a.count).slice(0, 5),
    recent: snapshot.recent.map((item) => ({
      attemptId: null,
      zh: item.sentence.zh,
      mode: item.mode === "FREE" ? "FREE" : "CLOZE",
      score: item.score,
      createdAt: item.createdAt,
    })),
  };
}

/**
 * mock 三源（正确率、错误类型、演示数据）→ 视图模型。
 * 演示数据没有连续天数字段，从 14 天趋势的末尾反推连续非零天数，与引擎 calcStreak 同口径。
 * @param accuracy 正确率响应
 * @param tags 错误类型响应
 * @param demo 演示数据
 * @returns 视图模型
 */
function viewFromMock(
  accuracy: Awaited<ReturnType<typeof getAccuracy>>,
  tags: Awaited<ReturnType<typeof getErrorTags>>,
  demo: StatsDemo,
): StatsView {
  let streakDays = 0;
  for (let index = demo.dailyTrend.length - 1; index >= 0; index -= 1) {
    if ((demo.dailyTrend[index]?.attempts ?? 0) > 0) {
      streakDays += 1;
    } else {
      break;
    }
  }
  return {
    streakDays,
    totalAttempts: accuracy.totalAttempts,
    averageScore: accuracy.averageScore,
    daily14: demo.dailyTrend.map((day) => ({ key: day.date, count: day.attempts })),
    errorTags: [...tags.items]
      .sort((a, b) => b.count - a.count)
      .slice(0, 5)
      .map((item) => ({ tag: item.tag, count: item.count })),
    recent: demo.recent.slice(0, 5).map((item) => ({
      attemptId: item.attemptId,
      zh: item.zh,
      mode: item.mode,
      score: item.score,
      createdAt: item.createdAt,
    })),
  };
}

/**
 * 三个大数（48px 衬线数字 + mono 单位与小标；≤760px 数字收窄为 34px，同原型媒体查询）。
 * @param props value 数值、unit 单位字、label 底部小标
 */
function BigNum({ value, unit, label }: { value: number; unit: string; label: string }) {
  return (
    <div>
      <b className="block font-serif text-[48px] font-medium leading-[0.95] text-ink max-[760px]:text-[34px]">
        {value}
        <small className="ml-[3px] font-mono text-sm font-normal text-ink-faint">{unit}</small>
      </b>
      <span className="mt-3 block font-mono text-[10px] tracking-[0.16em] text-ink-faint">
        {label}
      </span>
    </div>
  );
}

/**
 * 统计页：view-stats——三个大数、14 天柱状、错误集中 Top5 与最近记录，别的不放。
 *
 * 数据源分两种模式：真实模式直接读本地引擎 getStatsSnapshot（本地引擎能力，适配层的
 * /analytics/* 也由它投影）；mock 模式沿用 getAccuracy / getErrorTags 契约接口与
 * getMockStatsDemo 演示数据。最近记录行只有内容与得分两列，不提供回看入口。
 */
export function StatsPage() {
  const mock = isMockEnabled();
  const to = localDateString(new Date());
  const from = localDateString(new Date(Date.now() - 29 * DAY_MS));

  /** 真实模式：引擎统计快照一次取全（连续、累计、平均、趋势、考点、最近记录）。 */
  const engineQuery = useQuery({
    queryKey: ["stats", "engine-snapshot"],
    queryFn: () => getStatsSnapshot(),
    enabled: !mock,
  });

  const accuracyQuery = useQuery({
    queryKey: queryKeys.accuracy({ from, to }),
    queryFn: ({ signal }) => getAccuracy({ from, to }, signal),
    enabled: mock,
  });
  const errorTagsQuery = useQuery({
    queryKey: queryKeys.errorTags({ from, to }),
    queryFn: ({ signal }) => getErrorTags({ from, to }, signal),
    enabled: mock,
  });
  const demoQuery = useQuery({
    queryKey: ["stats", "mock-demo"],
    queryFn: async (): Promise<StatsDemo> => {
      const { getMockStatsDemo } = await import("@/mocks/handlers");
      return getMockStatsDemo();
    },
    enabled: mock,
  });

  const pending = mock
    ? accuracyQuery.isPending || errorTagsQuery.isPending || demoQuery.isPending
    : engineQuery.isPending;
  const error = mock
    ? (accuracyQuery.error ?? errorTagsQuery.error ?? demoQuery.error)
    : engineQuery.error;

  const view = mock
    ? accuracyQuery.data && errorTagsQuery.data && demoQuery.data
      ? viewFromMock(accuracyQuery.data, errorTagsQuery.data, demoQuery.data)
      : null
    : engineQuery.data
      ? viewFromEngine(engineQuery.data)
      : null;

  const retry = () => {
    if (mock) {
      void accuracyQuery.refetch();
      void errorTagsQuery.refetch();
      void demoQuery.refetch();
    } else {
      void engineQuery.refetch();
    }
  };

  const maxDaily = Math.max(1, ...(view?.daily14.map((day) => day.count) ?? [1]));

  return (
    <div>
      <header>
        <h1 className="page-title">统计</h1>
        <p className="page-sub">只留三个数，和一条趋势。指标多了会开始骗自己。</p>
      </header>

      {pending ? (
        <LoadingBlock label="正在汇总统计…" rows={4} />
      ) : error ? (
        <ErrorState error={error} onRetry={retry} />
      ) : view ? (
        <>
          <section className="grid grid-cols-3 gap-6 border-b border-rule pt-1.5 pb-[34px] max-[760px]:gap-3.5">
            <BigNum value={view.streakDays} unit="天" label="连续" />
            <BigNum value={view.totalAttempts} unit="句" label="累计" />
            {/* 平均分取整展示；契约允许一位小数，原型大数位不留小数。 */}
            <BigNum value={Math.round(view.averageScore)} unit="分" label="平均" />
          </section>

          <section className="mt-[52px]">
            <h2 className="section-title">最近 14 天</h2>
            <div
              className="flex h-[92px] items-end gap-[5px]"
              role="img"
              aria-label="最近 14 天每日作答数柱状图，今天的柱子为实心朱红"
            >
              {view.daily14.map((day, index) => {
                const isToday = index === view.daily14.length - 1;
                const height =
                  day.count > 0 ? Math.max(6, Math.round((day.count / maxDaily) * 100)) : 2;
                return (
                  <span
                    key={day.key}
                    title={`${day.count} 句`}
                    className={cn(
                      "min-h-[2px] flex-1 transition-[height] duration-500 ease-[cubic-bezier(0.2,0.8,0.2,1)]",
                      day.count > 0 ? (isToday ? "bg-vermilion" : "bg-vermilion/35") : "bg-rule",
                    )}
                    style={{ height: `${height}%` }}
                  />
                );
              })}
            </div>
            <div className="mt-[10px] flex justify-between font-mono text-[9px] tracking-[0.14em] text-ink-faint">
              <span>14 天前</span>
              <span>今天</span>
            </div>
          </section>

          <section className="mt-[52px]">
            <h2 className="section-title">错误集中在</h2>
            {view.errorTags.length === 0 ? (
              <p className="text-xs text-ink-faint">还没有错误记录。保持住。</p>
            ) : (
              <div className="flex flex-col gap-4">
                {view.errorTags.map((item) => {
                  // 已按次数降序，第一项就是最大值，条宽相对它取百分比（同原型取整口径）。
                  const topMax = view.errorTags[0]?.count ?? 1;
                  return (
                    <div
                      key={item.tag}
                      className="grid grid-cols-[72px_1fr_36px] items-center gap-4 text-xs text-ink-muted"
                    >
                      <span className="truncate">{item.tag}</span>
                      <span className="relative h-[5px] bg-rule">
                        <span
                          className="absolute inset-y-0 left-0 bg-vermilion/70"
                          style={{
                            width: `${Math.round((item.count / Math.max(topMax, 1)) * 100)}%`,
                          }}
                        />
                      </span>
                      <span className="text-right font-mono text-[10px] text-ink-faint">
                        {item.count}
                      </span>
                    </div>
                  );
                })}
              </div>
            )}
          </section>

          <section className="mt-[52px]">
            <h2 className="section-title">最近记录</h2>
            {view.recent.length === 0 ? (
              <p className="text-xs text-ink-faint">还没有提交记录。</p>
            ) : (
              <ul>
                {view.recent.map((item) => (
                  <li
                    key={item.attemptId ?? `${item.createdAt}-${item.zh ?? ""}`}
                    className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3.5 gap-y-2.5 border-b border-rule py-[18px] min-[761px]:grid-cols-[minmax(0,1fr)_auto_auto] min-[761px]:gap-5"
                  >
                    <div className="min-w-0">
                      <p className="truncate font-serif text-base leading-[1.5] text-ink">
                        {item.zh ?? "（题目已不在题库中）"}
                      </p>
                      <p className="mt-[7px] font-mono text-[10px] tracking-[0.1em] text-ink-faint">
                        {item.mode === "CLOZE" ? "填空" : "整句"} · {relativeDay(item.createdAt)}
                      </p>
                    </div>

                    <span
                      className={cn(
                        "justify-self-end whitespace-nowrap rounded-[2px] border px-2 py-1 font-mono text-[10px] tracking-[0.1em]",
                        item.score === 100
                          ? "border-moss/40 text-moss"
                          : "border-rule text-ink-faint",
                      )}
                    >
                      {item.score === null ? "看答案" : `${item.score} 分`}
                    </span>

                    {/* 第三列为空占位：原型第三列无操作，保留空 span 维持三列网格节奏。 */}
                    <span className="justify-self-end max-[760px]:col-start-2" />
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      ) : null}
    </div>
  );
}
