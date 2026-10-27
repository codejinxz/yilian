import { z } from "zod";
import {
  attemptKindSchema,
  fsrsStateSchema,
  graderSchema,
  idSchema,
  isoDateTimeSchema,
  pageResponseSchema,
  practiceModeSchema,
  reviewStateSchema,
} from "./common";
import { sentenceViewSchema } from "./content";

export const attemptSlotSchema = z.object({
  chunkIndex: z.number().int().nonnegative(),
  value: z.string(),
});

export const attemptAnswerSchema = z.object({
  schemaVersion: z.literal(1),
  mode: practiceModeSchema,
  slots: z.array(attemptSlotSchema).optional(),
  text: z.string().optional(),
  revealed: z.boolean().optional(),
});

export type AttemptAnswer = z.infer<typeof attemptAnswerSchema>;

export type PracticeMode = z.infer<typeof practiceModeSchema>;

export const attemptRequestSchema = z.object({
  attemptId: z.string().uuid(),
  sentenceId: z.string().min(1),
  sentenceVersionId: idSchema,
  mode: practiceModeSchema,
  kind: attemptKindSchema,
  retryOfId: z.string().uuid().nullable(),
  answer: attemptAnswerSchema,
  hintsUsed: z.number().int().min(0).max(3),
  durationMs: z.number().int().min(0).max(86_400_000),
  clientCreatedAt: isoDateTimeSchema.optional(),
});

export type AttemptRequest = z.infer<typeof attemptRequestSchema>;

export const gradingAnnotationSchema = z.object({
  key: z.string().min(1),
  chunkIndex: z.number().int().nonnegative(),
  tag: z.string().min(1),
  expected: z.array(z.string()),
  received: z.string(),
  correct: z.boolean(),
  message: z.string().nullable(),
  minimalFix: z.string().nullable(),
});

export type GradingAnnotation = z.infer<typeof gradingAnnotationSchema>;

export const gradingResultSchema = z.object({
  schemaVersion: z.literal(1),
  score: z.number().min(0).max(100).nullable(),
  maxScore: z.number().int().positive(),
  hitCount: z.number().int().nonnegative(),
  totalCount: z.number().int().nonnegative(),
  annotations: z.array(gradingAnnotationSchema),
  summary: z.string(),
});

export type GradingResult = z.infer<typeof gradingResultSchema>;

export const reviewSchema = z.object({
  id: idSchema,
  sentenceId: z.string().min(1),
  // TODO(mock): 演示数据，真实接口落地后删除——mock 复习卡 join 句子补中文，避免复习行显示裸 ID。
  zh: z.string().nullable().optional(),
  mode: practiceModeSchema,
  state: reviewStateSchema,
  fsrsState: fsrsStateSchema,
  dueAt: isoDateTimeSchema,
  stability: z.number().nonnegative(),
  difficulty: z.number().nonnegative(),
  reps: z.number().int().nonnegative(),
  lapses: z.number().int().nonnegative(),
  scheduledDays: z.number().int().nonnegative(),
  rating: z.number().int().min(1).max(4).nullable().optional(),
  lastRating: z.number().int().min(1).max(4).nullable().optional(),
  lastReviewedAt: isoDateTimeSchema.nullable().optional(),
});

export type Review = z.infer<typeof reviewSchema>;

export const attemptResponseSchema = z.object({
  attemptId: z.string().uuid(),
  sentenceId: z.string().min(1),
  sentenceVersionId: idSchema,
  mode: practiceModeSchema,
  kind: attemptKindSchema,
  score: z.number().min(0).max(100).nullable(),
  maxScore: z.number().int().positive(),
  result: gradingResultSchema,
  grading: z.object({
    id: idSchema,
    sequence: z.number().int().positive(),
    grader: graderSchema,
    normalizerVersion: z.string(),
    rubricVersion: z.string(),
    createdAt: isoDateTimeSchema,
  }),
  review: reviewSchema.nullable(),
  reference: z.object({
    ref: z.string(),
    note: z.string().nullable(),
  }),
  nextAction: z.object({
    retryRequired: z.boolean(),
    canReveal: z.boolean(),
  }),
  replayed: z.boolean(),
});

export type AttemptResponse = z.infer<typeof attemptResponseSchema>;

export const practiceSentenceSchema = sentenceViewSchema.extend({
  zh: z.string(),
  ref: z.string(),
  // 音标必须 optional：当前服务端与本地引擎都不产出该字段，加了会话才能兼容旧数据与旧响应；
  // 只有 mock 演示数据携带，练习页也仅在字段存在时渲染「音标」入口。
  ipa: z.string().optional(),
  chunks: z.array(
    z.object({
      index: z.number().int().nonnegative(),
      zh: z.string(),
      answers: z.array(z.string()).min(1),
      tag: z.string().nullable(),
      note: z.string().nullable(),
    }),
  ),
  blank: z.array(z.number().int().nonnegative()),
  tags: z.array(z.string()),
});

export type PracticeSentence = z.infer<typeof practiceSentenceSchema>;

export const nextPracticeSchema = z.object({
  sentence: practiceSentenceSchema,
  review: reviewSchema.nullable(),
  selection: z.enum(["DUE", "NEW", "MANUAL"]),
});

export type NextPractice = z.infer<typeof nextPracticeSchema>;

export const wrongQuestionSchema = z.object({
  sentenceId: z.string(),
  mode: practiceModeSchema,
  state: reviewStateSchema,
  dueAt: isoDateTimeSchema,
  lastScore: z.number().nullable(),
  lastAttemptId: z.string().nullable(),
  wrongCount: z.number().int().nonnegative(),
  tags: z.array(z.string()),
  zh: z.string(),
  annotations: z.array(
    z.object({
      key: z.string(),
      tag: z.string(),
      message: z.string().nullable(),
      disputed: z.boolean(),
    }),
  ),
});

export const wrongQuestionPageSchema = pageResponseSchema(wrongQuestionSchema);

export const reviewPageSchema = pageResponseSchema(reviewSchema);

export const appealResponseSchema = z.object({
  appealId: idSchema,
  status: z.enum(["PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN"]),
  kind: z.enum(["APPEAL", "DISPUTE"]),
  annotationKey: z.string(),
});

export type AppealResponse = z.infer<typeof appealResponseSchema>;

export const todaySchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  timezone: z.string(),
  dueCount: z.number().int().nonnegative(),
  doneCount: z.number().int().nonnegative(),
  goal: z.number().int().positive(),
  correctRate: z.number().min(0).max(100),
  streakDays: z.number().int().nonnegative(),
});

export type Today = z.infer<typeof todaySchema>;

/** mock 用户设置对象：仅存在于 mock 数据层，字段与真实用户设置保持同一契约。 */
export interface UserSettings {
  timezone: string;
  dailyGoal: number;
  ratingStandard: string;
  desiredRetention: number;
  theme: string;
  notificationsEnabled: boolean;
}
