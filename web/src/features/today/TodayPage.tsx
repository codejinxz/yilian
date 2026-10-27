import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { ErrorState, LoadingBlock, Notice } from "@/components/feedback";
import { useOnlineStatus } from "@/hooks/use-online-status";
import { getBanks } from "@/lib/api/content";
import { getWrongQuestions } from "@/lib/api/practice";
import { localDateString } from "@/lib/format";
import { getStatsSnapshot } from "@/lib/local-engine";
import { isMockEnabled } from "@/lib/mock-flag";
import { todayQueryOptions } from "@/lib/query";
import { cn } from "@/lib/utils";

/** 周点阵的数据点：key 为本地日期（YYYY-MM-DD），count 为当天作答数。 */
interface WeekDot {
  key: string;
  count: number;
}

/** 今日页在契约数字之外的本地补充：续练行的最近一句与周点阵。 */
interface TodayExtras {
  /** 最近一次作答的中文句；拿不到（引擎无记录）时隐藏续练行。 */
  lastZh: string | null;
  /** 末 7 天（今天在最后）的作答点阵。 */
  week: WeekDot[];
}

/**
 * 底部轻提示：toast（墨底纸字、底部居中、1.8 秒自动消失）。
 * @param props message 当前文案、visible 是否可见（控制淡入淡出）
 */
function TodayToast({ message, visible }: { message: string | null; visible: boolean }) {
  return (
    <div
      aria-live="polite"
      className={cn(
        "pointer-events-none fixed inset-x-0 bottom-8 z-50 flex justify-center transition-all duration-200",
        visible && message ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0",
      )}
    >
      {message ? (
        <p className="rounded-sm bg-ink px-4 py-[9px] text-[12.5px] leading-5 text-paper">
          {message}
        </p>
      ) : null}
    </div>
  );
}

/**
 * 管理 toast 状态的最小 hook；只在本页使用，避免为一条提示引入全局依赖。
 * @returns message/visible 状态与 show 触发函数
 */
function useToast() {
  const [message, setMessage] = useState<string | null>(null);
  const [visible, setVisible] = useState(false);
  const timerRef = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
      }
    },
    [],
  );

  const show = (text: string) => {
    setMessage(text);
    setVisible(true);
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
    }
    timerRef.current = window.setTimeout(() => setVisible(false), 1800);
  };

  return { message, visible, show };
}

/**
 * 今日启动台：view-today——一个日期、一个特大到期数、一个占满宽的主按钮，
 * 下面是题库 chips、续练行与周点阵；不做仪表盘。
 *
 * 数据分两层：今日数字走 todayQueryOptions（真实模式由本地适配层应答、mock 模式走
 * mocks/handlers，页面不感知差别）；chips 用 getBanks/getWrongQuestions 契约接口；
 * 续练行与周点阵直接读本地引擎快照（本地引擎能力），mock 模式降级用演示数据的同位字段。
 */
export function TodayPage() {
  const navigate = useNavigate();
  const online = useOnlineStatus();
  const mock = isMockEnabled();
  const toast = useToast();

  const todayQuery = useQuery(todayQueryOptions);
  const today = todayQuery.data;

  /** 题库 chips：公开题库一行一个；游客匿名可读，失败时整组隐藏不影响主按钮。 */
  const banksQuery = useQuery({
    queryKey: ["content", "banks", "today-chips"],
    queryFn: ({ signal }) => getBanks({ page: 1, size: 100, status: "PUBLISHED" }, signal),
  });

  /** 错题 chip 的计数；size=1 只取 total，不拉明细。 */
  const wrongQuery = useQuery({
    queryKey: ["practice", "wrong-questions", "today-count"],
    queryFn: ({ signal }) => getWrongQuestions({ page: 1, size: 1 }, signal),
  });

  /**
   * 本地引擎能力：续练行与周点阵。
   * mock 模式引擎无数据，续练行改用演示数据的最近一条、点阵用演示趋势——
   * 都是同一语义字段的合法来源，不另外造假数字。
   */
  const extrasQuery = useQuery({
    queryKey: ["today", "extras", mock],
    queryFn: async (): Promise<TodayExtras> => {
      if (isMockEnabled()) {
        const { getMockStatsDemo } = await import("@/mocks/handlers");
        const demo = getMockStatsDemo();
        return {
          lastZh: demo.recent[0]?.zh ?? null,
          week: demo.dailyTrend.slice(-7).map((day) => ({ key: day.date, count: day.attempts })),
        };
      }
      const snapshot = await getStatsSnapshot();
      return {
        lastZh: snapshot.recent[0]?.sentence.zh ?? null,
        week: snapshot.daily14.slice(-7).map((count, index) => ({
          key: localDateString(new Date(Date.now() - (6 - index) * 86_400_000)),
          count,
        })),
      };
    },
  });

  const banks = banksQuery.data?.items ?? [];
  /** 全部题库句子总数；为 0 时主按钮点击只提示不跳转（空题库 toast）。 */
  const bankSentenceTotal = banksQuery.data
    ? banks.reduce((sum, bank) => sum + bank.sentenceCount, 0)
    : null;

  const dateNow = new Date();
  const dateParts = today?.date
    ? today.date.split("-").map(Number)
    : [dateNow.getFullYear(), dateNow.getMonth() + 1, dateNow.getDate()];
  const dateLabel = `${dateParts[1]} 月 ${dateParts[2]} 日`;

  /**
   * 主按钮三态文案（todayStart）：今天已练过→继续下一句；有到期→开始 N 句；否则热身。
   */
  const doneCount = today?.doneCount ?? 0;
  const dueCount = today?.dueCount ?? 0;
  const ctaLabel =
    doneCount > 0
      ? `继续第 ${doneCount + 1} 句`
      : dueCount > 0
        ? `开始 ${dueCount} 句`
        : `做 10 句热身`;

  const dateHeader = (
    <div className="flex items-center gap-2.5 font-mono text-[11px] tracking-[0.12em] text-ink-faint">
      <span>今日</span>
      <span className="h-[3px] w-[3px] rounded-full bg-ink-faint" aria-hidden="true" />
      <span>{dateLabel}</span>
    </div>
  );

  if (todayQuery.isPending) {
    return (
      <div className="mx-auto w-full max-w-[560px] pt-[8vh]">
        {dateHeader}
        <div className="mt-[18px]">
          <LoadingBlock label="正在读取今日队列…" rows={3} />
        </div>
      </div>
    );
  }

  if (todayQuery.isError && !today) {
    return (
      <div className="mx-auto w-full max-w-[560px] pt-[8vh]">
        {dateHeader}
        <ErrorState
          className="mt-6"
          error={todayQuery.error}
          onRetry={() => void todayQuery.refetch()}
        />
      </div>
    );
  }

  if (!today) {
    return null;
  }

  const lastZh = extrasQuery.data?.lastZh ?? null;
  const week = extrasQuery.data?.week ?? [];

  return (
    <div className="mx-auto w-full max-w-[560px] pt-[8vh]">
      {/* 页面可访问标题：原型用特大到期数做视觉主标，h1 收进屏幕阅读器以保证标题层级完整。 */}
      <h1 className="sr-only">今日</h1>
      {dateHeader}

      <p className="mt-[18px] font-serif text-[clamp(40px,8vw,64px)] font-medium leading-[1.05] tracking-[-0.01em] text-ink">
        {today.dueCount} 句到期
        <small className="ml-2.5 font-mono text-[0.26em] font-normal tracking-[0.12em] text-ink-faint">
          今天
        </small>
      </p>
      <p className="mb-[34px] mt-3 font-mono text-xs tracking-[0.06em] text-ink-faint">
        已练 {today.doneCount} / {today.goal} · 连续 {today.streakDays} 天
      </p>

      {!online ? (
        <Notice className="mb-5" tone="info">
          当前离线，显示上次读取的今日数字。
        </Notice>
      ) : null}

      {todayQuery.isError ? (
        <Notice className="mb-5" tone="danger">
          刷新今日数字失败：{todayQuery.error.message}。当前展示上次数据，可稍后重试。
        </Notice>
      ) : null}

      {/* 主按钮三态文案todayStart；空题库时点击只提示不跳转。
          不写静态 aria-label：可见文案本身即可访问名，静态覆盖反而让读屏用户
          听不到「继续第 N 句 / 开始 N 句 / 做 10 句热身」的状态差异。
          热身态的 excludeIds 语义：getNextPractice 契约只支持单个 excludeSentenceId，
          传不了整组排除，先做普通跳转，由练习页的取题顺序（到期优先）兜底。 */}
      <Link
        to="/practice"
        onClick={(event) => {
          if (bankSentenceTotal === 0) {
            event.preventDefault();
            toast.show("这个题库还没有句子");
          }
        }}
        className="flex w-full cursor-pointer items-center justify-center gap-2 rounded-[2px] border border-vermilion bg-vermilion px-[17px] py-[17px] text-sm tracking-[0.06em] text-white transition-colors duration-200 hover:border-vermilion-deep hover:bg-vermilion-deep"
      >
        {ctaLabel}
        {/* kbd：10px mono、1px currentColor 边、2px 圆角、1px 4px 内距、65% 透明度 */}
        <span
          className="rounded-[2px] border border-current px-1 py-px font-mono text-[10px] font-normal leading-none opacity-65"
          aria-hidden="true"
        >
          ↵
        </span>
      </Link>

      {banksQuery.isError ? null : (
        <div className="mt-[18px] flex flex-wrap gap-2.5">
          {banks.map((bank) => (
            <Link
              key={bank.id}
              to="/practice"
              search={(prev) => ({ ...prev, bankId: bank.id })}
              title={`从「${bank.name}」开始练习`}
              onClick={(event) => {
                if (bank.sentenceCount === 0) {
                  event.preventDefault();
                  toast.show("这个题库还没有句子");
                }
              }}
              className="inline-flex cursor-pointer items-center rounded-[2px] border border-rule px-[11px] py-[7px] font-mono text-[11px] tracking-[0.02em] text-ink-muted hover:border-vermilion hover:text-vermilion"
            >
              {bank.name.split(" · ")[0]} {bank.sentenceCount}
            </Link>
          ))}
          {/* 错题没有独立的 bankId（不是题库表里的行），startToday('errors')：进练习页的错题集取题。
              契约里用布尔 search 表达：链接序列化为 ?errors=true，validateSearch 归一为 errors: true；
              不能裸写 ?errors=1——查询串会被 JSON 解析成数字 1，匹配不上 errors===true 的契约。 */}
          <Link
            to="/practice"
            search={{ errors: true }}
            title="答错的句子都在错题集里，进去逐条练"
            className="inline-flex cursor-pointer items-center rounded-[2px] border border-rule px-[11px] py-[7px] font-mono text-[11px] tracking-[0.02em] text-ink-muted hover:border-vermilion hover:text-vermilion"
          >
            错题 {wrongQuery.data?.total ?? "…"}
          </Link>
        </div>
      )}

      {lastZh ? (
        <div className="mt-7 flex items-center justify-between gap-3.5 border-t border-rule pt-5 text-xs text-ink-muted">
          <span className="truncate font-serif">上次停在「{lastZh.slice(0, 14)}…」</span>
          <button
            type="button"
            onClick={() => void navigate({ to: "/practice" })}
            className="shrink-0 cursor-pointer border-b border-vermilion/35 bg-transparent pb-px text-vermilion"
          >
            继续
          </button>
        </div>
      ) : null}

      <div className="mt-9 flex gap-2" role="img" aria-label="最近 7 天，有作答的日期标为朱红">
        {week.map((dot) => (
          <span
            key={dot.key}
            title={`${dot.key} · ${dot.count} 句`}
            className={cn("h-2 w-2 rounded-full", dot.count > 0 ? "bg-vermilion" : "bg-rule")}
          />
        ))}
      </div>

      <TodayToast message={toast.message} visible={toast.visible} />
    </div>
  );
}
