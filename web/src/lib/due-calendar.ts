/**
 * 7 日到期日历的纯聚合工具：把到期时间按用户 IANA 时区日界落进
 * 今天..+6 天七个桶。引擎快照与契约分页两条数据源都经由这里产出
 * 同一日历形状，渲染层不感知来源差异。
 */

import { localDateString } from "@/lib/format";

/** 日历窗口长度：今天起共 7 桶。 */
export const DUE_CALENDAR_DAYS = 7;

/** 单个到期桶：一个用户时区日界键内的学习中到期卡计数。 */
export interface DueBucket {
  /** 本地日界键 YYYY-MM-DD。 */
  date: string;
  /** 落进该日界键的到期卡数。 */
  count: number;
  /** 是否今日桶。 */
  isToday: boolean;
}

/** 7 日到期日历视图。 */
export interface DueCalendar {
  /** 恒 7 项，按今天..+6 天排列。 */
  buckets: DueBucket[];
  /** 分桶是否覆盖全部到期卡；false 表示只统计了已取回的分页，展示须附上限提示。 */
  complete: boolean;
}

/**
 * 把到期时间聚合成 7 个日界桶：日界口径与今日页/统计页一致，都经
 * localDateString 按用户时区解析；已过今日日界的逾期卡并入今天桶，
 * 7 天外的卡不属于本窗口，不进任何桶。
 * @param dueAts 学习中卡的到期时间（ISO 字符串），非法值跳过
 * @param now 今日桶的基准时间
 * @param complete 分桶是否覆盖全量到期卡（分页截断时为 false）
 * @returns 7 桶日历
 */
export function buildDueCalendar(
  dueAts: ReadonlyArray<string>,
  now: Date,
  complete = true,
): DueCalendar {
  const todayKey = localDateString(now);
  const [year, month, day] = todayKey.split("-").map(Number);
  // 桶位日界键用本地时区构造：跨月/跨年与夏令时滚动由 Date 自身保证。
  const keys = Array.from({ length: DUE_CALENDAR_DAYS }, (_, offset) =>
    localDateString(new Date(year, month - 1, day + offset)),
  );
  const buckets = keys.map((date, index) => ({ date, count: 0, isToday: index === 0 }));
  for (const dueAt of dueAts) {
    const date = new Date(dueAt);
    if (Number.isNaN(date.getTime())) {
      continue;
    }
    const key = localDateString(date);
    const index = keys.indexOf(key);
    if (index >= 0) {
      buckets[index].count += 1;
    } else if (key < todayKey) {
      buckets[0].count += 1;
    }
  }
  return { buckets, complete };
}
