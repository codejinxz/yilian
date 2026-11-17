import { z } from "zod";
import {
  auditOutcomeSchema,
  errorModuleSchema,
  idSchema,
  isoDateTimeSchema,
  pageResponseSchema,
  userRoleSchema,
} from "./common";

export const adminUserSchema = z.object({
  id: idSchema,
  username: z.string(),
  email: z.string().nullable(),
  role: userRoleSchema,
  enabled: z.boolean(),
  /** 是否已启用两步验证（只投影开关，不含密钥材料）。 */
  totpEnabled: z.boolean(),
  deleted: z.boolean(),
  createdAt: isoDateTimeSchema,
  lastLoginAt: isoDateTimeSchema.nullable(),
  disabledReason: z.string().nullable(),
  // TODO(mock): 演示数据，真实接口落地后删除——管理端用户行的「答题数」列。
  attemptsCount: z.number().int().optional(),
});

export type AdminUser = z.infer<typeof adminUserSchema>;

export const adminUserPageSchema = pageResponseSchema(adminUserSchema);

/** 管理员重置密码回执：临时口令明文只随本次响应返回一次。 */
export const adminPasswordResetSchema = z.object({
  temporaryPassword: z.string().min(1),
  userId: idSchema,
  username: z.string(),
});

export type AdminPasswordReset = z.infer<typeof adminPasswordResetSchema>;

export const userStatusRequestSchema = z.object({
  enabled: z.boolean(),
  reason: z.string().trim().min(1, "请填写原因").max(255),
});

export const userRoleRequestSchema = z.object({
  role: userRoleSchema,
  reason: z.string().trim().min(1, "请填写原因").max(255),
});

export const auditLogSchema = z.object({
  id: idSchema,
  module: z.string(),
  actorId: idSchema.nullable(),
  actorName: z.string(),
  actorRole: z.string().nullable(),
  clientIp: z.string().nullable(),
  action: z.string(),
  targetType: z.string().nullable(),
  targetId: z.string().nullable(),
  reason: z.string().nullable(),
  outcome: auditOutcomeSchema,
  durationMs: z.number().int().nonnegative().nullable(),
  createdAt: isoDateTimeSchema,
});

export type AuditLog = z.infer<typeof auditLogSchema>;

export const auditLogPageSchema = pageResponseSchema(auditLogSchema);

export const errorLogSchema = z.object({
  id: idSchema,
  traceId: z.string(),
  module: errorModuleSchema,
  exceptionClass: z.string(),
  message: z.string().nullable(),
  requestMethod: z.string().nullable(),
  requestPath: z.string().nullable(),
  userId: idSchema.nullable(),
  operator: z.string().nullable(),
  clientIp: z.string().nullable(),
  createdAt: isoDateTimeSchema,
});

export type ErrorLog = z.infer<typeof errorLogSchema>;

export const errorLogDetailSchema = errorLogSchema.extend({
  stackTrace: z.string().nullable(),
});

export type ErrorLogDetail = z.infer<typeof errorLogDetailSchema>;

export const errorLogPageSchema = pageResponseSchema(errorLogSchema);

export const rawAttemptSchema = z.object({
  attemptId: z.string(),
  userId: idSchema,
  sentenceId: z.string(),
  answer: z.unknown(),
  grading: z
    .object({
      id: idSchema,
      score: z.number().nullable(),
      result: z.unknown(),
    })
    .nullable(),
  auditId: idSchema,
});

export const importCommitRequestSchema = z.object({
  reason: z.string().trim().max(500).optional(),
});

export const importRollbackRequestSchema = z.object({
  reason: z.string().trim().min(1, "请填写回滚原因").max(500),
});

/**
 * 邀请码列表项：服务端只存哈希，列表只给展示前缀不给明文。
 */
export const inviteSummarySchema = z.object({
  codePrefix: z.string().min(1),
  used: z.boolean(),
  usedBy: z.string().nullable(),
  createdAt: isoDateTimeSchema,
});

/** 邀请码列表项类型。 */
export type InviteSummary = z.infer<typeof inviteSummarySchema>;

/**
 * 新生成的邀请码：明文 code 只在此响应中出现一次，之后任何接口都拿不回。
 */
export const createdInviteSchema = z.object({
  code: z.string().min(1),
  codePrefix: z.string().min(1),
  createdAt: isoDateTimeSchema,
});

/** 新生成的邀请码类型。 */
export type CreatedInvite = z.infer<typeof createdInviteSchema>;

/**
 * 管理端申诉行（API §8.16）：终审上下文含 AI 初裁意见、题目摘要与用户主张。
 * decisionVerdict 从 appeals.decision JSON 抽取，UNAVAILABLE/null 表示无有效 AI 意见。
 */
export const adminAppealSchema = z.object({
  id: idSchema,
  attemptId: z.string().min(1),
  annotationKey: z.string().min(1),
  sentenceId: z.string().min(1),
  chunkIndex: z.number().int().nonnegative(),
  kind: z.enum(["APPEAL", "DISPUTE"]),
  status: z.enum(["PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN"]),
  proposedAnswer: z.string().nullable(),
  reason: z.string().nullable(),
  decisionVerdict: z.string().nullable(),
  decisionReason: z.string().nullable(),
  decidedBy: z.string().nullable(),
  decidedAt: isoDateTimeSchema.nullable(),
  modelSnapshot: z.string().nullable(),
  promptVersionId: idSchema.nullable(),
  createdAt: isoDateTimeSchema,
  sentenceZh: z.string().nullable(),
  username: z.string().nullable(),
});

/** 管理端申诉行类型。 */
export type AdminAppeal = z.infer<typeof adminAppealSchema>;

/** 管理端申诉分页。 */
export const adminAppealPageSchema = pageResponseSchema(adminAppealSchema);
