/**
 * 管理端九页签共用的演示常量：难度目录、考点目录与动作码中文名。
 * 难度/考点目录目前没有服务端配置端点，属于前端演示态；服务端切片落地后迁到系统配置。
 */

/**
 * 管理端九页签：id 与原型 adminTabs 的 data-tab 一致，顺序即页头导航顺序。
 * 管理端是单页（#/admin）：页签不产生独立路由，由 AdminLayout 按 tab 条件渲染。
 */
export const ADMIN_TABS = [
  { id: "overview", label: "概览" },
  { id: "users", label: "用户" },
  { id: "sentences", label: "题目" },
  { id: "banks", label: "题库" },
  { id: "generation", label: "出题" },
  { id: "models", label: "模型" },
  { id: "audit", label: "操作日志" },
  { id: "errors", label: "异常" },
  { id: "ops", label: "运维" },
] as const;

/** 页签 id 全集（含缺省的概览）。 */
export type AdminTabId = (typeof ADMIN_TABS)[number]["id"];

/**
 * URL 页签参数的合法取值；概览是缺省页签，不占参数位（/admin 直接渲染概览）。
 * 与 ADMIN_TABS 保持同一顺序，router 的 validateSearch 据此放行或丢弃。
 */
export const ADMIN_URL_TABS = [
  "users",
  "sentences",
  "banks",
  "generation",
  "models",
  "audit",
  "errors",
  "ops",
] as const satisfies ReadonlyArray<Exclude<AdminTabId, "overview">>;

/** URL 上 tab 参数的类型；不含概览。 */
export type AdminTabParam = (typeof ADMIN_URL_TABS)[number];

/**
 * 页签导航用的类型化 search 值：概览不带参数（URL 回到干净的 /admin），其余页签带 tab 参数。
 * @param id 页签 id
 * @returns 类型化导航可用的 search 对象
 */
export function adminTabSearch(id: AdminTabId): { tab?: AdminTabParam } {
  return id === "overview" ? {} : { tab: id };
}

/** 难度目录；id 与句子 level 字段取值一致。 */
export const LEVELS: Array<{ id: string; label: string }> = [
  { id: "CET4", label: "四级" },
  { id: "CET6", label: "六级" },
  { id: "TEM4", label: "专四" },
  { id: "TEM8", label: "专八" },
  { id: "CATTI", label: "CATTI" },
  { id: "IELTS", label: "雅思" },
  { id: "TOEFL", label: "托福" },
  { id: "CUSTOM", label: "自建" },
];

/** 「自建」是保留难度，不允许删除。 */
export const RESERVED_LEVEL_ID = "CUSTOM";

/**
 * 查难度中文名；未知 id 原样返回，避免导入数据里的新难度显示为空。
 * @param id 难度 id
 * @returns 可展示的难度名
 */
export function levelLabel(id: string | null | undefined): string {
  if (!id) {
    return "—";
  }
  return LEVELS.find((level) => level.id === id)?.label ?? id;
}

/** AI 出题表单的考点目录。 */
export const GEN_TAGS = ["时态", "搭配", "倒装", "从句", "非谓语", "虚拟语气"] as const;

/**
 * mock 演示模型名；与模型页目录（ModelsPage 的 DeepSeek · deepseek-chat）同源，
 * 出题页按钮与 toast 只引用这里，改演示模型时只动一处。
 */
export const MOCK_GEN_MODEL_LABEL = "DeepSeek · deepseek-chat";

/** 操作日志的动作码中文名；覆盖种子数据与九页签演示写入的全部动作。 */
export const ACTION_LABELS: Record<string, string> = {
  LOGIN: "登录",
  USER_DISABLE: "停用账号",
  USER_ENABLE: "恢复账号",
  USER_DELETE: "删除账号",
  ROLE_CHANGE: "变更角色",
  VIEW_ATTEMPT_RAW: "查看作答",
  RESET_PASSWORD: "重置密码",
  EXPORT_USER_DATA: "导出数据",
  INVITE_CREATE: "生成邀请码",
  BANK_CREATE: "新建题库",
  BANK_UPDATE: "更新题库",
  BANK_DELETE: "删除题库",
  SENTENCE_UPDATE: "编辑题目",
  SENTENCE_STATUS: "变更题目状态",
  SENTENCE_DISABLE: "停用题目",
  SENTENCE_BULK_STATUS: "批量变更题目状态",
  SENTENCE_BULK_MOVE: "批量移动题目",
  IMPORT_COMMIT: "提交导入",
  IMPORT_ROLLBACK: "回滚导入",
  PASSWORD_CHANGE: "修改密码",
  MODEL_CONFIG_UPDATE: "更新模型配置",
  MODEL_TOGGLE: "启停模型",
  PROMPT_UPDATE: "更新 Prompt",
  SESSION_REVOKE: "吊销会话",
};

/**
 * 查动作中文名；未知动作码原样返回，保证新动作可见不吞掉。
 * @param action 动作码
 * @returns 可展示的动作名
 */
export function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

/** 操作日志筛选下拉的动作选项。 */
export const ACTION_FILTER_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "all", label: "全部操作" },
  ...Object.entries(ACTION_LABELS).map(([value, label]) => ({ value, label })),
];

const DAY_MS = 86_400_000;

/**
 * 相对今天的日期描述：今天 / 昨天 / N 天前；的 fmtWhen。
 * @param iso ISO 时间
 * @returns 可直接展示的相对日期
 */
export function relativeDayLabel(iso: string): string {
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
  if (diff === 1) {
    return "昨天";
  }
  return `${diff} 天前`;
}

/**
 * 相对今天的日期 + 时钟；用于日志与活动流的时间列。
 * @param iso ISO 时间
 * @returns 例如「今天 14:20」「3 天前 09:12」
 */
export function relativeTimeLabel(iso: string): string {
  const time = new Date(iso);
  if (Number.isNaN(time.getTime())) {
    return "—";
  }
  const clock = `${String(time.getHours()).padStart(2, "0")}:${String(time.getMinutes()).padStart(2, "0")}`;
  return `${relativeDayLabel(iso)} ${clock}`;
}

/** 时间范围筛选值。 */
export type TimeRangeValue = "all" | "today" | "7d" | "30d";

/** 时间范围筛选选项。 */
export const TIME_RANGE_OPTIONS: Array<{ value: TimeRangeValue; label: string }> = [
  { value: "all", label: "全部时间" },
  { value: "today", label: "今天" },
  { value: "7d", label: "近 7 天" },
  { value: "30d", label: "近 30 天" },
];
