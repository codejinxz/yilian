/** 内容生命周期状态的中文标签；管理端与导入页共用。 */
export const CONTENT_STATUS_LABELS: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  DISABLED: "已停用",
  RECYCLED: "回收站",
  ARCHIVED: "已归档",
};

/** 导入批次状态的中文标签。 */
export const IMPORT_STATUS_LABELS: Record<string, string> = {
  PREVIEWING: "预览中",
  PREVIEWED: "待提交",
  COMMITTING: "提交中",
  COMMITTED: "已提交",
  FAILED: "失败",
  ROLLED_BACK: "已回滚",
  EXPIRED: "已过期",
};

/** 导入行状态的中文标签。 */
export const IMPORT_ROW_STATUS_LABELS: Record<string, string> = {
  VALID: "通过",
  INVALID: "未通过",
  IMPORTED: "已入库",
  SKIPPED: "跳过",
};

/** 操作日志的稳定动作码与中文名。 */
export const AUDIT_ACTION_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "LOGIN", label: "登录" },
  { value: "USER_DISABLE", label: "停用账号" },
  { value: "USER_ENABLE", label: "启用账号" },
  { value: "ROLE_CHANGE", label: "变更角色" },
  { value: "VIEW_ATTEMPT_RAW", label: "查看答题原文" },
  { value: "BANK_CREATE", label: "新建题库" },
  { value: "BANK_UPDATE", label: "更新题库" },
  { value: "BANK_DELETE", label: "删除题库" },
  { value: "SENTENCE_STATUS", label: "变更题目状态" },
  { value: "SENTENCE_DISABLE", label: "停用题目" },
  { value: "IMPORT_COMMIT", label: "提交导入" },
  { value: "IMPORT_ROLLBACK", label: "回滚导入" },
  { value: "PASSWORD_CHANGE", label: "修改密码" },
];

/** 异常日志的模块选项。 */
export const MODULE_OPTIONS = [
  "account",
  "content",
  "practice",
  "ai",
  "admin",
  "analytics",
  "system",
] as const;

export type TimeRange = "all" | "today" | "7d" | "30d";

/**
 * 把时间范围转成 YYYY-MM-DD 的 from/to；「全部」返回空对象。
 * @param range 时间范围
 * @returns 日期筛选参数
 */
export function timeRangeParams(range: TimeRange): { from?: string; to?: string } {
  if (range === "all") {
    return {};
  }
  const now = new Date();
  const days = range === "today" ? 0 : range === "7d" ? 6 : 29;
  const from = new Date(now.getTime() - days * 86_400_000);
  const toDate = (date: Date) => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  };
  return { from: toDate(from), to: toDate(now) };
}

/** 管理端状态标记的颜色。 */
export function statusTone(status: string): "neutral" | "vermilion" | "moss" {
  if (status === "PUBLISHED" || status === "COMMITTED" || status === "SUCCESS") {
    return "moss";
  }
  if (status === "RECYCLED" || status === "DISABLED" || status === "FAILURE") {
    return "vermilion";
  }
  return "neutral";
}
