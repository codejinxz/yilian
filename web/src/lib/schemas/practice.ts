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

/**
 * 复习卡排程投影：提交响应内嵌卡（API §6.2/§6.3）与复习队列卡（API §6.7）的共有字段。
 */
const reviewCardBaseSchema = z.object({
  id: idSchema,
  state: reviewStateSchema,
  fsrsState: fsrsStateSchema,
  dueAt: isoDateTimeSchema,
  stability: z.number().nonnegative(),
  difficulty: z.number().nonnegative(),
  reps: z.number().int().nonnegative(),
  lapses: z.number().int().nonnegative(),
  scheduledDays: z.number().int().nonnegative(),
});

export const reviewSchema = reviewCardBaseSchema.extend({
  sentenceId: z.string().min(1),
  // TODO(mock): 演示数据，真实接口落地后删除——mock 复习卡 join 句子补中文，避免复习行显示裸 ID。
  zh: z.string().nullable().optional(),
  mode: practiceModeSchema,
  rating: z.number().int().min(1).max(4).nullable().optional(),
  lastRating: z.number().int().min(1).max(4).nullable().optional(),
  lastReviewedAt: isoDateTimeSchema.nullable().optional(),
});

export type Review = z.infer<typeof reviewSchema>;

/**
 * 提交/详情响应的内嵌复习卡（API §6.2/§6.3）：契约只有排程投影，不带 sentenceId/mode；
 * 字段按可选放行，因为本地引擎的离线响应复用完整队列卡形状（此前必填导致
 * 服务端 200 响应 parse 失败，页面误报「提交未确认」而数据实际已入库+排程）。
 */
export const attemptReviewSchema = reviewSchema.extend({
  sentenceId: z.string().min(1).optional(),
  mode: practiceModeSchema.optional(),
});

export type AttemptReview = z.infer<typeof attemptReviewSchema>;

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
  review: attemptReviewSchema.nullable(),
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
  // 025 起：申诉提交同步 LLM 初裁，成功时回执带 AI 意见（verdict/理由/裁决者/时间）；
  // 初裁失败、限流或未配置模型时回执 verdict="UNAVAILABLE"（029 起服务端如实发送，与存储的
  // decision JSON 同源）、decidedBy/decidedAt 为 null/缺省（不阻塞提交）；DISPUTE 恒为 null。
  verdict: z.enum(["SUPPORT", "OPPOSE", "UNCERTAIN", "UNAVAILABLE"]).nullable().optional(),
  decisionReason: z.string().nullable().optional(),
  decidedBy: z.string().nullable().optional(),
  decidedAt: z.string().nullable().optional(),
});

export type AppealResponse = z.infer<typeof appealResponseSchema>;

/** 题目答疑回复（API §6.12）：disclaimer 为固定免责标注，前端必须常驻展示。 */
export const tutorAskResponseSchema = z.object({
  answer: z.string().min(1),
  disclaimer: z.string().min(1),
});

export type TutorAskResponse = z.infer<typeof tutorAskResponseSchema>;

/**
 * 模型复核结果（API §6.13）：追加判分记录（grader=MODEL），不覆盖首判。
 * 复核不改复习排程与错题队列（首判口径），前端据此展示「复核不影响复习计划」注记。
 */
export const modelReviewSchema = z.object({
  score: z.number().int().min(0).max(100),
  reason: z.string().min(1),
  modelSnapshot: z.string().min(1),
  latencyMs: z.number().int().nonnegative().nullable(),
  createdAt: isoDateTimeSchema,
  sequence: z.number().int().positive(),
  originalScore: z.number().int().min(0).max(100),
});

export type ModelReview = z.infer<typeof modelReviewSchema>;

export const todaySchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  timezone: z.string(),
  dueCount: z.number().int().nonnegative(),
  doneCount: z.number().int().nonnegative(),
  goal: z.number().int().positive(),
  // 服务端在今日无判分提交时返回 null（TodayView.correctRate）；页面不消费该字段。
  correctRate: z.number().min(0).max(100).nullable(),
  streakDays: z.number().int().nonnegative(),
});

export type Today = z.infer<typeof todaySchema>;

/** 积压降载状态（API §3.6）：overload=true 时客户端只推最紧急 pushLimit 道。 */
export const overloadViewSchema = z.object({
  dueCount: z.number().int().nonnegative(),
  threshold: z.number().int().nonnegative(),
  pushLimit: z.number().int().nonnegative(),
  overload: z.boolean(),
});

export type OverloadView = z.infer<typeof overloadViewSchema>;

/** 顺延结果（API §3.6）：服务端把推送上限之外的到期卡顺延 3 天。 */
export const postponeViewSchema = z.object({
  postponed: z.number().int().nonnegative(),
  kept: z.number().int().nonnegative(),
});

export type PostponeView = z.infer<typeof postponeViewSchema>;

/** mock 用户设置对象：仅存在于 mock 数据层，字段与真实用户设置保持同一契约。 */
export interface UserSettings {
  timezone: string;
  dailyGoal: number;
  ratingStandard: string;
  desiredRetention: number;
  theme: string;
  notificationsEnabled: boolean;
}
