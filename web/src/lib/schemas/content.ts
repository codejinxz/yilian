import { z } from "zod";
import {
  contentStatusSchema,
  idSchema,
  isoDateTimeSchema,
  pageResponseSchema,
  visibilitySchema,
} from "./common";

export const chunkSchema = z.object({
  index: z.number().int().nonnegative(),
  zh: z.string().min(1),
  answers: z.array(z.string().min(1)).min(1),
  tag: z.string().nullable(),
  note: z.string().nullable(),
});

export type Chunk = z.infer<typeof chunkSchema>;

export const sentenceViewSchema = z.object({
  id: z.string().min(1),
  version: z.number().int().nonnegative(),
  sentenceVersionId: idSchema,
  questionVersion: z.number().int().positive(),
  status: contentStatusSchema,
  deleted: z.boolean(),
  zh: z.string().optional(),
  ref: z.string().optional(),
  note: z.string().nullable().optional(),
  chunks: z.array(chunkSchema).optional(),
  blank: z.array(z.number().int().nonnegative()).optional(),
  tags: z.array(z.string()).optional(),
  level: z.string().nullable().optional(),
  // 音标（可选）：本地引擎经此 schema 缓存句子，缺它会把远端 ipa 静默剥离，
  // 练习页「音标」切换就只剩 mock 路径可达。
  ipa: z.string().optional(),
});

export type SentenceView = z.infer<typeof sentenceViewSchema>;

export const bankViewSchema = z.object({
  id: idSchema,
  ownerId: idSchema.nullable(),
  name: z.string().min(1),
  description: z.string().nullable(),
  visibility: visibilitySchema,
  status: contentStatusSchema,
  version: z.number().int().nonnegative(),
  sentenceCount: z.number().int().nonnegative(),
});

export type BankView = z.infer<typeof bankViewSchema>;

export const bankPageSchema = pageResponseSchema(bankViewSchema);

export const versionedSentencesSchema = z.object({
  version: z.number().int().nonnegative(),
  items: z.array(sentenceViewSchema),
  nextSinceVersion: z.number().int().nonnegative(),
  hasMore: z.boolean(),
});

export const bankCreateRequestSchema = z.object({
  name: z.string().trim().min(1, "请填写题库名称").max(128, "题库名称最多 128 个字符"),
  description: z.string().trim().max(500, "说明最多 500 个字符").nullable(),
  visibility: visibilitySchema,
});

export const bankUpdateRequestSchema = bankCreateRequestSchema
  .partial()
  .extend({ status: contentStatusSchema.optional() });

export type BankCreateRequest = z.infer<typeof bankCreateRequestSchema>;

export const sentenceStatusRequestSchema = z.object({
  status: contentStatusSchema,
  reason: z.string().trim().min(1, "请填写原因").max(255, "原因最多 255 个字符"),
});

export const importRowErrorSchema = z.object({
  code: z.string().min(1),
  field: z.string().nullable(),
  message: z.string().min(1),
});

export type ImportRowError = z.infer<typeof importRowErrorSchema>;

export const importRowSchema = z.object({
  rowNo: z.number().int().positive(),
  externalKey: z.string().nullable(),
  status: z.enum(["VALID", "INVALID", "IMPORTED", "SKIPPED"]),
  errors: z.array(importRowErrorSchema),
  sentenceId: z.string().nullable(),
  sentenceVersionId: idSchema.nullable(),
});

export type ImportRow = z.infer<typeof importRowSchema>;

export const importErrorSummarySchema = z.object({
  schemaVersion: z.number().int(),
  code: z.string(),
  message: z.string(),
  sampleRows: z.array(z.number().int()).optional(),
});

export const importBatchViewSchema = z.object({
  batchId: z.string().min(1),
  bankId: idSchema,
  status: z.enum([
    "PREVIEWING",
    "PREVIEWED",
    "COMMITTING",
    "COMMITTED",
    "FAILED",
    "ROLLED_BACK",
    "EXPIRED",
  ]),
  format: z.enum(["JSON", "CSV"]).optional(),
  originalFilename: z.string().optional(),
  totalRows: z.number().int().nonnegative(),
  validRows: z.number().int().nonnegative(),
  invalidRows: z.number().int().nonnegative(),
  importedRows: z.number().int().nonnegative(),
  createdAt: isoDateTimeSchema.optional(),
  previewedAt: isoDateTimeSchema.nullable().optional(),
  committedAt: isoDateTimeSchema.nullable().optional(),
  errorSummary: importErrorSummarySchema.nullable().optional(),
});

export type ImportBatchView = z.infer<typeof importBatchViewSchema>;

export const importPreviewSchema = z.object({
  batchId: z.string().min(1),
  bankId: idSchema,
  status: z.literal("PREVIEWED"),
  format: z.enum(["JSON", "CSV"]),
  totalRows: z.number().int().nonnegative(),
  validRows: z.number().int().nonnegative(),
  invalidRows: z.number().int().nonnegative(),
  rows: z.array(importRowSchema),
  errorSummary: importErrorSummarySchema.nullable(),
});

export type ImportPreview = z.infer<typeof importPreviewSchema>;

export const importBatchPageSchema = pageResponseSchema(importBatchViewSchema);

export const importBatchDetailSchema = z.object({
  batch: importBatchViewSchema.extend({
    format: z.enum(["JSON", "CSV"]),
    originalFilename: z.string(),
    createdAt: isoDateTimeSchema,
    previewedAt: isoDateTimeSchema.nullable(),
    committedAt: isoDateTimeSchema.nullable(),
    errorSummary: importErrorSummarySchema.nullable(),
  }),
  rows: pageResponseSchema(importRowSchema),
});

export type ImportBatchDetail = z.infer<typeof importBatchDetailSchema>;
