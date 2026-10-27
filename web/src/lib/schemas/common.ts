import { z } from "zod";

/**
 * BIGINT ID 统一序列化为字符串；同时接受 number，兼容尚未收口的旧服务端，输出始终是 string。
 */
export const idSchema = z
  .union([z.string().min(1), z.number().int().nonnegative()])
  .transform((value) => String(value));

export const userRoleSchema = z.enum(["USER", "ADMIN"]);

export const visibilitySchema = z.enum(["PUBLIC", "PRIVATE"]);

export const contentStatusSchema = z.enum([
  "DRAFT",
  "PUBLISHED",
  "DISABLED",
  "RECYCLED",
  "ARCHIVED",
]);

export const practiceModeSchema = z.enum(["CLOZE", "FREE"]);

export const attemptKindSchema = z.enum(["FIRST", "RETRY", "REVEAL"]);

export const reviewStateSchema = z.enum(["NEW", "LEARNING", "MASTERED"]);

export const fsrsStateSchema = z.enum(["NEW", "LEARNING", "REVIEW", "RELEARNING"]);

export const graderSchema = z.enum(["LOCAL", "GRAMMAR", "MODEL", "HUMAN"]);

export const importFormatSchema = z.enum(["JSON", "CSV"]);

export const importStatusSchema = z.enum([
  "PREVIEWING",
  "PREVIEWED",
  "COMMITTING",
  "COMMITTED",
  "FAILED",
  "ROLLED_BACK",
  "EXPIRED",
]);

export const importRowStatusSchema = z.enum(["VALID", "INVALID", "IMPORTED", "SKIPPED"]);

export const auditOutcomeSchema = z.enum(["SUCCESS", "FAILURE"]);

export const errorModuleSchema = z.enum([
  "account",
  "content",
  "practice",
  "ai",
  "admin",
  "analytics",
  "system",
]);

export const appealKindSchema = z.enum(["APPEAL", "DISPUTE"]);

export const appealStatusSchema = z.enum(["PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN"]);

export const isoDateTimeSchema = z.string().min(1);

/**
 * 构造 PageResponse 的 Zod schema；items 使用调用方提供的条目 schema。
 * @param item 列表条目 schema
 * @returns PageResponse schema
 */
export function pageResponseSchema<T extends z.ZodType>(item: T) {
  return z.object({
    items: z.array(item),
    page: z.number().int().positive(),
    size: z.number().int().positive(),
    total: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
    hasNext: z.boolean(),
  });
}

/** PageResponse 的通用 TS 类型。 */
export type PageResponse<T> = {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  hasNext: boolean;
};
