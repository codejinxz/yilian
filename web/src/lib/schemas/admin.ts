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
  deleted: z.boolean(),
  createdAt: isoDateTimeSchema,
  lastLoginAt: isoDateTimeSchema.nullable(),
  disabledReason: z.string().nullable(),
  // TODO(mock): 演示数据，真实接口落地后删除——管理端用户行的「答题数」列。
  attemptsCount: z.number().int().optional(),
});

export type AdminUser = z.infer<typeof adminUserSchema>;

export const adminUserPageSchema = pageResponseSchema(adminUserSchema);

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
  // TODO(mock): 演示数据，真实接口落地后删除——对象的人类可读名，替代裸 targetId（UUID）展示。
  targetLabel: z.string().nullable().optional(),
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
