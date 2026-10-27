/**
 * 把 UTC ISO 时间格式化为用户本地时间；非法时间原样返回，避免页面崩溃。
 * @param value 后端返回的 ISO 时间或 null
 * @param timezone 用户 IANA 时区；省略时用浏览器时区
 * @param options 覆盖 Intl 的日期/时间样式
 * @returns 可直接展示的时间文本
 */
export function formatDateTime(
  value: string | null | undefined,
  timezone?: string | null,
  options: Intl.DateTimeFormatOptions = { dateStyle: "medium", timeStyle: "short" },
): string {
  if (!value) {
    return "—";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  try {
    return new Intl.DateTimeFormat("zh-CN", {
      ...options,
      ...(timezone ? { timeZone: timezone } : {}),
    }).format(date);
  } catch {
    return new Intl.DateTimeFormat("zh-CN", options).format(date);
  }
}

/**
 * 只展示月日；用于复习到期与最近记录。
 * @param value ISO 时间
 * @param timezone 用户 IANA 时区
 * @returns 例如「10月25日」
 */
export function formatMonthDay(value: string | null | undefined, timezone?: string | null): string {
  return formatDateTime(value, timezone, { month: "long", day: "numeric" });
}

/**
 * 相对到期描述：今天/明天/已到期 N 天。
 * @param dueAt ISO 到期时间
 * @returns 可直接展示的短文案
 */
export function describeDue(dueAt: string): string {
  const due = new Date(dueAt).getTime();
  if (Number.isNaN(due)) {
    return dueAt;
  }
  const diffMs = due - Date.now();
  const day = 86_400_000;
  if (diffMs <= 0) {
    const overdueDays = Math.max(1, Math.ceil(-diffMs / day));
    return overdueDays === 1 ? "已到期" : `已到期 ${overdueDays} 天`;
  }
  if (diffMs < day) {
    return "今天到期";
  }
  const days = Math.ceil(diffMs / day);
  return `${days} 天后到期`;
}

/**
 * 百分比展示；输入已是 0–100 的百分数。
 * @param value 百分数
 * @param digits 小数位
 * @returns 例如「72.5%」
 */
export function formatPercent(value: number, digits = 1): string {
  return `${value.toFixed(digits).replace(/\.0$/, "")}%`;
}

/**
 * 转成用户本地日期的 YYYY-MM-DD；统计接口的 from/to 需要本地日期。
 * @param date 日期对象
 * @param timezone 用户 IANA 时区
 * @returns YYYY-MM-DD
 */
export function localDateString(date: Date, timezone?: string | null): string {
  try {
    const parts = new Intl.DateTimeFormat("en-CA", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      ...(timezone ? { timeZone: timezone } : {}),
    }).formatToParts(date);
    const part = (type: string) => parts.find((item) => item.type === type)?.value ?? "";
    return `${part("year")}-${part("month")}-${part("day")}`;
  } catch {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }
}

/**
 * 把秒/毫秒耗时格式化为短文案。
 * @param durationMs 毫秒；空值显示「—」
 * @returns 例如「12ms」或「1.2s」
 */
export function formatDuration(durationMs: number | null | undefined): string {
  if (durationMs === null || durationMs === undefined) {
    return "—";
  }
  return durationMs < 1000 ? `${durationMs}ms` : `${(durationMs / 1000).toFixed(1)}s`;
}

/**
 * 截短 ID；列表里展示完整 ULID 会挤占正文空间。
 * @param id 原始 id
 * @param length 保留字符数
 * @returns 截短后的 id
 */
export function shortId(id: string, length = 8): string {
  if (id.length <= length) {
    return id;
  }
  return `${id.slice(0, length)}…`;
}
