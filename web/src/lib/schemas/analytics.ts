import { z } from "zod";
import { attemptKindSchema, isoDateTimeSchema, practiceModeSchema } from "./common";

export const accuracySchema = z.object({
  from: z.string(),
  to: z.string(),
  totalAttempts: z.number().int().nonnegative(),
  // 服务端在区间内无判分记录时为 null；页面按 0 展示。
  correctRate: z.number().min(0).max(100).nullable(),
  averageScore: z.number().min(0).max(100).nullable(),
  byMode: z.array(
    z.object({
      mode: practiceModeSchema,
      attempts: z.number().int().nonnegative(),
      correctRate: z.number().min(0).max(100).nullable(),
    }),
  ),
  // §7.1 扩展（D8）：最近 14 天逐日与最近提交；mock 与本地引擎投影允许缺省，
  // 页面只在登录态消费这两个字段（游客统计页走引擎快照）。
  daily14: z
    .array(
      z.object({
        date: z.string(),
        attempts: z.number().int().nonnegative(),
        correctRate: z.number().min(0).max(100).nullable(),
      }),
    )
    .optional(),
  recent: z
    .array(
      z.object({
        attemptId: z.string().nullable(),
        sentenceId: z.string(),
        mode: practiceModeSchema,
        kind: attemptKindSchema,
        score: z.number().min(0).max(100).nullable(),
        createdAt: isoDateTimeSchema,
      }),
    )
    .optional(),
});

export type Accuracy = z.infer<typeof accuracySchema>;

export const errorTagsSchema = z.object({
  from: z.string(),
  to: z.string(),
  items: z.array(
    z.object({
      tag: z.string(),
      count: z.number().int().nonnegative(),
      ratio: z.number().min(0).max(100),
    }),
  ),
});

export type ErrorTags = z.infer<typeof errorTagsSchema>;
