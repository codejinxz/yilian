import { z } from "zod";
import { apiRequest } from "@/lib/api";
import {
  type AdminPasswordReset,
  type AdminUser,
  adminPasswordResetSchema,
  adminUserPageSchema,
  adminUserSchema,
  auditLogPageSchema,
  type CreatedInvite,
  createdInviteSchema,
  errorLogDetailSchema,
  errorLogPageSchema,
  type InviteSummary,
  inviteSummarySchema,
  rawAttemptSchema,
} from "@/lib/schemas/admin";
import { type PageResponse, pageResponseSchema } from "@/lib/schemas/common";
import {
  type BankView,
  bankPageSchema,
  bankViewSchema,
  type ImportBatchDetail,
  type ImportBatchView,
  type ImportPreview,
  importBatchDetailSchema,
  importBatchPageSchema,
  importBatchViewSchema,
  importPreviewSchema,
  type SentenceView,
  sentenceViewSchema,
} from "@/lib/schemas/content";
import { jsonRequest, parseData, toQueryString } from "./shared";

/**
 * 管理端用户列表。
 * @param params 分页、关键字、角色与状态
 * @param signal 取消信号
 * @returns 分页用户
 */
export async function getAdminUsers(
  params: {
    page?: number;
    size?: number;
    keyword?: string;
    role?: "USER" | "ADMIN";
    status?: "active" | "disabled" | "deleted";
  } = {},
  signal?: AbortSignal,
): Promise<PageResponse<AdminUser>> {
  const data = await apiRequest<unknown>(`/admin/users${toQueryString(params)}`, { signal });
  return parseData(adminUserPageSchema, data);
}

/**
 * 停用/恢复账号；停用必须带原因。
 * @param userId 用户 id
 * @param input enabled 与原因
 * @returns 更新后的用户
 */
export async function updateUserStatus(
  userId: string,
  input: { enabled: boolean; reason: string },
): Promise<AdminUser> {
  const data = await jsonRequest<unknown>(
    `/admin/users/${encodeURIComponent(userId)}/status`,
    "PATCH",
    input,
  );
  return parseData(adminUserSchema, data);
}

/**
 * 变更角色。
 * @param userId 用户 id
 * @param input role 与原因
 * @returns 更新后的用户
 */
export async function updateUserRole(
  userId: string,
  input: { role: "USER" | "ADMIN"; reason: string },
): Promise<AdminUser> {
  const data = await jsonRequest<unknown>(
    `/admin/users/${encodeURIComponent(userId)}/role`,
    "PATCH",
    input,
  );
  return parseData(adminUserSchema, data);
}

/**
 * 管理员重置用户密码（018 AC-6a）：服务端生成一次性临时口令，目标用户回到强制改密态并全部下线。
 * 明文只随本响应返回一次，前端须提示管理员立即转交。
 * @param userId 用户 id
 * @param reason 审计原因
 * @returns 一次性临时口令与目标用户
 */
export async function resetAdminUserPassword(
  userId: string,
  reason: string,
): Promise<AdminPasswordReset> {
  const data = await jsonRequest<unknown>(
    `/admin/users/${encodeURIComponent(userId)}/reset-password`,
    "POST",
    { reason },
  );
  return parseData(adminPasswordResetSchema, data);
}

/**
 * 操作日志检索。
 * @param params 分页、时间、模块、动作、操作者与关键字
 * @param signal 取消信号
 * @returns 分页操作日志
 */
export async function getAuditLogs(
  params: {
    page?: number;
    size?: number;
    from?: string;
    to?: string;
    module?: string;
    action?: string;
    actorId?: string;
    keyword?: string;
  } = {},
  signal?: AbortSignal,
) {
  const data = await apiRequest<unknown>(`/admin/audit-logs${toQueryString(params)}`, { signal });
  return parseData(auditLogPageSchema, data);
}

/**
 * 异常日志列表；列表不含堆栈。
 * @param params 分页、时间、模块与关键字
 * @param signal 取消信号
 * @returns 分页异常日志
 */
export async function getErrorLogs(
  params: {
    page?: number;
    size?: number;
    from?: string;
    to?: string;
    module?: string;
    keyword?: string;
  } = {},
  signal?: AbortSignal,
) {
  const data = await apiRequest<unknown>(`/admin/error-logs${toQueryString(params)}`, { signal });
  return parseData(errorLogPageSchema, data);
}

/**
 * 异常详情；只有这里返回 stackTrace。
 * @param id 异常 id
 * @param signal 取消信号
 * @returns 含堆栈的异常详情
 */
export async function getErrorLogDetail(id: string, signal?: AbortSignal) {
  const data = await apiRequest<unknown>(`/admin/error-logs/${encodeURIComponent(id)}`, { signal });
  return parseData(errorLogDetailSchema, data);
}

/**
 * 管理端题库列表；包含 DRAFT/DISABLED/RECYCLED。
 * @param params 分页、关键字、状态与可见性
 * @param signal 取消信号
 * @returns 分页题库
 */
export async function getAdminBanks(
  params: {
    page?: number;
    size?: number;
    keyword?: string;
    status?: string;
    visibility?: "PUBLIC" | "PRIVATE";
  } = {},
  signal?: AbortSignal,
): Promise<PageResponse<BankView>> {
  const data = await apiRequest<unknown>(`/admin/banks${toQueryString(params)}`, { signal });
  return parseData(bankPageSchema, data);
}

/**
 * 新建题库。
 * @param input 名称、说明与可见性
 * @returns 新题库
 */
export async function createAdminBank(input: {
  name: string;
  description: string | null;
  visibility: "PUBLIC" | "PRIVATE";
}): Promise<BankView> {
  const data = await jsonRequest<unknown>("/admin/banks", "POST", input);
  return parseData(bankViewSchema, data);
}

/**
 * 更新题库名称、说明、可见性或状态。
 * @param bankId 题库 id
 * @param input 待更新字段
 * @returns 更新后的题库
 */
export async function updateAdminBank(
  bankId: string,
  input: Partial<{
    name: string;
    description: string | null;
    visibility: "PUBLIC" | "PRIVATE";
    status: string;
  }>,
): Promise<BankView> {
  const data = await jsonRequest<unknown>(
    `/admin/banks/${encodeURIComponent(bankId)}`,
    "PATCH",
    input,
  );
  return parseData(bankViewSchema, data);
}

/**
 * 软删题库。
 * @param bankId 题库 id
 * @param reason 删除原因
 */
export async function deleteAdminBank(bankId: string, reason: string): Promise<void> {
  await jsonRequest<unknown>(`/admin/banks/${encodeURIComponent(bankId)}`, "DELETE", { reason });
}

/**
 * 管理端题目列表。
 * @param bankId 题库 id
 * @param params 分页、关键字与状态
 * @param signal 取消信号
 * @returns 分页题目
 */
export async function getAdminBankSentences(
  bankId: string,
  params: { page?: number; size?: number; keyword?: string; status?: string } = {},
  signal?: AbortSignal,
): Promise<PageResponse<SentenceView>> {
  const data = await apiRequest<unknown>(
    `/admin/banks/${encodeURIComponent(bankId)}/sentences${toQueryString(params)}`,
    { signal },
  );
  return parseData(pageResponseSchema(sentenceViewSchema), data);
}

/**
 * 停用/恢复/回收题目。
 * @param sentenceId 题目 id
 * @param input 新状态与原因
 * @returns 更新后的题目
 */
export async function updateSentenceStatus(
  sentenceId: string,
  input: { status: string; reason: string },
): Promise<SentenceView> {
  const data = await jsonRequest<unknown>(
    `/admin/sentences/${encodeURIComponent(sentenceId)}/status`,
    "PATCH",
    input,
  );
  return parseData(sentenceViewSchema, data);
}

/**
 * 上传文件并生成预览批次；不写题目。
 * @param input 文件、目标题库与格式
 * @returns 预览结果与行级错误
 */
export async function previewImport(input: {
  file: File;
  bankId: string;
  format: "JSON" | "CSV";
}): Promise<ImportPreview> {
  const body = new FormData();
  body.append("file", input.file, input.file.name);
  body.append("bankId", input.bankId);
  body.append("format", input.format);
  const data = await apiRequest<unknown>(
    "/admin/uploads/preview",
    { method: "POST", body },
    { timeoutMs: 120_000 },
  );
  return parseData(importPreviewSchema, data);
}

/**
 * 提交已预览的批次；单事务，失败整批回滚。
 * @param batchId 批次 id
 * @param reason 可选审计原因
 * @returns 批次状态
 */
export async function commitImport(batchId: string, reason?: string): Promise<ImportBatchView> {
  const data = await jsonRequest<unknown>(
    `/admin/uploads/${encodeURIComponent(batchId)}/commit`,
    "POST",
    reason ? { reason } : {},
  );
  return parseData(importBatchViewSchema, data);
}

/**
 * 回滚已提交批次。
 * @param batchId 批次 id
 * @param reason 回滚原因
 * @returns 批次状态
 */
export async function rollbackImport(batchId: string, reason: string): Promise<ImportBatchView> {
  const data = await jsonRequest<unknown>(
    `/admin/uploads/${encodeURIComponent(batchId)}/rollback`,
    "POST",
    { reason },
  );
  return parseData(importBatchViewSchema, data);
}

/**
 * 导入批次列表。
 * @param params 分页、题库、状态与时间
 * @param signal 取消信号
 * @returns 分页批次
 */
export async function getImportBatches(
  params: {
    page?: number;
    size?: number;
    bankId?: string;
    status?: string;
    from?: string;
    to?: string;
  } = {},
  signal?: AbortSignal,
): Promise<PageResponse<ImportBatchView>> {
  const data = await apiRequest<unknown>(`/admin/uploads${toQueryString(params)}`, { signal });
  return parseData(importBatchPageSchema, data);
}

/**
 * 批次详情 + 行级错误分页。
 * @param batchId 批次 id
 * @param params 分页与行状态
 * @param signal 取消信号
 * @returns 批次与行
 */
export async function getImportBatch(
  batchId: string,
  params: { page?: number; size?: number; status?: string } = {},
  signal?: AbortSignal,
): Promise<ImportBatchDetail> {
  const data = await apiRequest<unknown>(
    `/admin/uploads/${encodeURIComponent(batchId)}${toQueryString(params)}`,
    { signal },
  );
  return parseData(importBatchDetailSchema, data);
}

/**
 * 查看答题原文；必须提供原因并写审计。
 * @param attemptId attempt id
 * @param reason 查看原因
 * @returns 原文、最新判分与审计 id
 */
export async function getRawAttempt(attemptId: string, reason: string) {
  const data = await jsonRequest<unknown>(
    `/admin/attempts/${encodeURIComponent(attemptId)}/raw`,
    "POST",
    { reason },
  );
  return parseData(rawAttemptSchema, data);
}

// ---------------------------------------------------------------------------
// 管理端九页签（概览/用户/题目/题库/出题/模型/日志/运维）新增客户端。
// 这些端点目前只有 mock 实现（VITE_USE_MOCK=true），schema 就近定义在客户端内，
// 服务端切片落地后再迁去 lib/schemas，避免跨模块共享半成品契约。
// ---------------------------------------------------------------------------

/** 概览页聚合数据的形状。 */
export const adminOverviewSchema = z.object({
  metrics: z.object({
    totalUsers: z.number().int().nonnegative(),
    activeToday: z.number().int().nonnegative(),
    totalAttempts: z.number().int().nonnegative(),
    estimatedCost: z.number().nonnegative(),
  }),
  active7d: z.array(z.object({ date: z.string(), count: z.number().int().nonnegative() })),
  todos: z.array(
    z.object({
      id: z.string(),
      title: z.string(),
      meta: z.string(),
      href: z.string(),
    }),
  ),
  activity: z.array(
    z.object({
      id: z.string(),
      who: z.string(),
      what: z.string(),
      at: z.string(),
    }),
  ),
});

/** 概览页聚合数据类型。 */
export type AdminOverview = z.infer<typeof adminOverviewSchema>;

/**
 * 管理端概览聚合：指标、近 7 天活跃、待处理与最近活动。
 * @param signal 取消信号
 * @returns 概览数据
 */
export async function getAdminOverview(signal?: AbortSignal): Promise<AdminOverview> {
  const data = await apiRequest<unknown>("/admin/overview", { signal });
  return parseData(adminOverviewSchema, data);
}

/**
 * 邀请码列表（新码在前）；服务端只存哈希，列表只回展示前缀不给明文。
 * @param signal 取消信号
 * @returns 邀请码数组
 */
export async function getInvites(signal?: AbortSignal): Promise<InviteSummary[]> {
  const data = await apiRequest<unknown>("/admin/invites", { signal });
  return parseData(z.object({ items: z.array(inviteSummarySchema) }), data).items;
}

/**
 * 生成一个新邀请码；一码一人，注册时核销，明文只随本响应出现一次。
 * @returns 含一次性明文的新邀请码
 */
export async function createInvite(): Promise<CreatedInvite> {
  const data = await jsonRequest<unknown>("/admin/invites", "POST");
  return parseData(createdInviteSchema, data);
}

/** 管理端题目视图：在 SentenceView 之上补充归属题库。 */
export const adminSentenceSchema = sentenceViewSchema.extend({
  bankId: z.string().min(1),
});

/** 管理端题目类型。 */
export type AdminSentence = z.infer<typeof adminSentenceSchema>;

/**
 * 跨题库题目列表；不传 status 时隐藏回收站。
 * @param params 分页、关键字、题库、难度与状态
 * @param signal 取消信号
 * @returns 分页题目
 */
export async function getAdminSentences(
  params: {
    page?: number;
    size?: number;
    keyword?: string;
    bankId?: string;
    level?: string;
    status?: string;
  } = {},
  signal?: AbortSignal,
): Promise<PageResponse<AdminSentence>> {
  const data = await apiRequest<unknown>(`/admin/sentences${toQueryString(params)}`, { signal });
  return parseData(pageResponseSchema(adminSentenceSchema), data);
}

/**
 * 批量变更题目状态（停用/启用/移入回收站）；一次操作写一条审计。
 * @param input ids、目标状态与原因
 * @returns 实际更新的句数
 */
export async function bulkUpdateSentenceStatus(input: {
  ids: string[];
  status: string;
  reason: string;
}): Promise<{ updated: number }> {
  const data = await jsonRequest<unknown>("/admin/sentences/bulk-status", "POST", input);
  return parseData(z.object({ updated: z.number().int().nonnegative() }), data);
}

/**
 * 批量移动题目到另一题库；一次操作写一条审计。
 * @param input ids、目标题库与原因
 * @returns 实际移动的句数
 */
export async function bulkMoveSentences(input: {
  ids: string[];
  bankId: string;
  reason: string;
}): Promise<{ moved: number }> {
  const data = await jsonRequest<unknown>("/admin/sentences/bulk-move", "POST", input);
  return parseData(z.object({ moved: z.number().int().nonnegative() }), data);
}

/**
 * 编辑题目的可变字段；参考译文与语块不在其中（改译文会破坏 chunks 与 ref 的一致性）。
 * @param sentenceId 题目 id
 * @param input 中文、难度、标签、解析、归属题库、状态与原因
 * @returns 更新后的题目
 */
export async function updateSentenceDetail(
  sentenceId: string,
  input: {
    zh?: string;
    level?: string;
    tags?: string[];
    note?: string;
    bankId?: string;
    status?: string;
    reason?: string;
  },
): Promise<AdminSentence> {
  const data = await jsonRequest<unknown>(
    `/admin/sentences/${encodeURIComponent(sentenceId)}`,
    "PATCH",
    input,
  );
  return parseData(adminSentenceSchema, data);
}

/** 用户抽屉的练习记录。 */
export const userAttemptSummarySchema = z.object({
  metrics: z.object({
    totalAttempts: z.number().int().nonnegative(),
    avgScore: z.number().int().nonnegative().nullable(),
    activeDays30: z.number().int().nonnegative(),
    streakDays: z.number().int().nonnegative(),
  }),
  heat: z.array(z.number().int().nonnegative()),
  items: z.array(
    z.object({
      attemptId: z.string(),
      sentenceId: z.string(),
      zh: z.string(),
      ref: z.string().nullable(),
      mode: z.enum(["CLOZE", "FREE"]),
      kind: z.enum(["FIRST", "RETRY", "REVEAL"]),
      score: z.number().nullable(),
      createdAt: z.string(),
    }),
  ),
});

/** 用户抽屉的练习记录类型。 */
export type UserAttemptSummary = z.infer<typeof userAttemptSummarySchema>;

/**
 * 某用户的练习记录与四项指标（管理端抽屉用）。
 * @param userId 用户 id
 * @param signal 取消信号
 * @returns 指标与最近作答
 */
export async function getUserAttempts(
  userId: string,
  signal?: AbortSignal,
): Promise<UserAttemptSummary> {
  const data = await apiRequest<unknown>(`/admin/users/${encodeURIComponent(userId)}/attempts`, {
    signal,
  });
  return parseData(userAttemptSummarySchema, data);
}

/**
 * 软删账号；删除必须填原因，30 天冷静期的语义由服务端保证。
 * @param userId 用户 id
 * @param reason 删除原因
 * @returns 删除后的用户快照
 */
export async function deleteAdminUser(userId: string, reason: string): Promise<AdminUser> {
  const data = await jsonRequest<unknown>(`/admin/users/${encodeURIComponent(userId)}`, "DELETE", {
    reason,
  });
  return parseData(adminUserSchema, data);
}

/**
 * 演示动作的通用审计入口：重置密码、导出数据、模型配置、运维吊销等
 * 目前没有专属服务端写端点的动作，用它在操作日志留下可见、有理由的审计记录。
 * 服务端校验动作白名单（012），未知动作回 40000。
 * @param input 动作、对象与原因
 * @returns 新审计条目 id
 */
export async function appendAuditEvent(input: {
  action: string;
  targetType?: string;
  targetId?: string;
  reason?: string;
}): Promise<{ id: string }> {
  const data = await jsonRequest<unknown>("/admin/audit-events", "POST", input);
  return parseData(z.object({ id: z.string() }), data);
}

/**
 * 导入 AI 生成的句子（出题页校验通过后调用）；整批入库（指纹重复整批拒绝），
 * 响应为 COMMITTED 批次视图。批次 id 仅用于溯源（upload 侧无批次记录），
 * 撤销走题目状态流转而不是批次回滚。
 * @param input 目标题库、校验通过的句子与可选原因
 * @returns 批次视图
 */
export async function importGeneratedSentences(input: {
  bankId: string;
  items: Array<Record<string, unknown>>;
  reason?: string;
}): Promise<ImportBatchView> {
  const data = await jsonRequest<unknown>("/admin/generation/import", "POST", input);
  return parseData(importBatchViewSchema, data);
}

/**
 * 停用/恢复题库；带原因（审计必填）。
 * @param bankId 题库 id
 * @param input 目标状态与原因
 * @returns 更新后的题库
 */
export async function updateAdminBankStatus(
  bankId: string,
  input: { status: string; reason: string },
): Promise<BankView> {
  const data = await jsonRequest<unknown>(
    `/admin/banks/${encodeURIComponent(bankId)}`,
    "PATCH",
    input,
  );
  return parseData(bankViewSchema, data);
}

// ---------------------------------------------------------------------------
// 答疑 LLM 配置（017 落地的真实端点，019 ModelsPage 状态中心接入）。
// 凭据只走 YILIAN_LLM_* 环境变量（AGENTS §3.4 密钥不入库）：GET 回 hasKey 不回读
// apiKey；PUT 只受理 enabled，baseUrl/model/apiKey 三个字段不传——服务端对非空值
// 一律 40000 拒绝（API §8.14.2）。
// ---------------------------------------------------------------------------

/** 管理端 LLM 配置视图（API §8.14.1）：enabled 为生效开关，baseUrl/model 为环境变量生效值。 */
export const llmConfigSchema = z.object({
  enabled: z.boolean(),
  baseUrl: z.string(),
  model: z.string(),
  hasKey: z.boolean(),
});

/** 管理端 LLM 配置视图类型。 */
export type LlmConfig = z.infer<typeof llmConfigSchema>;

/**
 * 读取答疑 LLM 配置；ModelsPage「统一模型配置」状态卡的数据源。
 * @param signal 取消信号
 * @returns enabled、生效 baseUrl/model 与 hasKey（API Key 不回读）
 */
export async function getLlmConfig(signal?: AbortSignal): Promise<LlmConfig> {
  const data = await apiRequest<unknown>("/admin/llm/config", { signal });
  return parseData(llmConfigSchema, data);
}

/**
 * 更新答疑开关；只传 enabled，凭证字段留给环境变量（传非空值会被服务端 40000 拒绝）。
 * @param input 目标开关状态
 * @returns 更新后的配置视图
 */
export async function updateLlmConfig(input: { enabled: boolean }): Promise<LlmConfig> {
  const data = await jsonRequest<unknown>("/admin/llm/config", "PUT", { enabled: input.enabled });
  return parseData(llmConfigSchema, data);
}

// 九页签页面直接从客户端取用户类型；schemas 保持既有导出面，不额外扩散。
export type { AdminUser };
