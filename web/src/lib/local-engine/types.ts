/**
 * 本地练习引擎的内部类型与存储抽象。
 *
 * 「游客不登录真实可用」的架构决策（DATA-MODEL D-001）要求练习/复习/统计全部在本机完成，
 * 因此引擎自带一份与 API 契约同构的持久模型。存储细节只通过本文件的接口暴露：
 * 引擎逻辑（engine.ts）不感知 IndexedDB / 内存实现的差别，测试可以注入内存实现。
 */
import type {
  AttemptRequest,
  AttemptResponse,
  PracticeSentence,
  Review,
} from "@/lib/schemas/practice";

/** 定级状态；键语义（yl-placed / 起点 / 错点标签），持久化到 localStorage。 */
export interface LocalPlacementState {
  /** 是否已完成定级；false 表示页面应继续展示定级流程。 */
  placed: boolean;
  /** 定级给出的起点档位（如「初级·日常」「中级·日常」）；未定级时为 null。 */
  startLevel: string | null;
  /** 定级中答错或跳过的考点标签，按出现顺序；页面据此提示「先补哪块」。 */
  missedTags: string[];
}

/**
 * 本地复习卡的公开投影：与契约一致的最小字段集。
 * 存储层实际保存 StoredReview（本投影的超集），页面只消费这些字段。
 */
export interface LocalReviewState {
  sentenceId: string;
  state: "NEW" | "LEARNING" | "MASTERED";
  /** ISO 到期时间；LEARNING 才有调度意义，MASTERED 保留最后一次到期值仅供展示。 */
  dueAt: string;
  reps: number;
  lapses: number;
  updatedAt: string;
}

/**
 * 引擎内的复习卡记录：在 API Review 契约之上补充 updatedAt。
 * 让同一份记录既能映射成 reviewSchema（适配层），又满足 LocalReviewState（页面）。
 */
export type StoredReview = Review & { updatedAt: string };

/** 引擎内的答题记录：request/response 成对保存，幂等重放与统计重算直接取用。 */
export interface StoredAttempt {
  attemptId: string;
  sentenceId: string;
  mode: "CLOZE" | "FREE";
  kind: "FIRST" | "RETRY" | "REVEAL";
  score: number | null;
  request: AttemptRequest;
  response: AttemptResponse;
  createdAt: string;
}

/** 「不认同 / 我觉得也对」的本地标记；append-only，只记状态不参与改判。 */
export interface StoredDispute {
  /** 稳定键：`${attemptId}:${annotationKey}`。 */
  key: string;
  attemptId: string;
  annotationKey: string;
  kind: "APPEAL" | "DISPUTE";
  status: "PENDING" | "ACCEPTED";
  createdAt: string;
}

/** 题库缓存条目：一次匿名拉取到的某个公开题库的完整句子集。 */
export interface CachedBank {
  bankId: string;
  sentences: PracticeSentence[];
  fetchedAt: string;
}

/** 错题行的公开投影；在契约最小字段之上补充适配层构造 wrongQuestionSchema 所需的明细。 */
export interface LocalWrongQuestionRow {
  sentence: PracticeSentence;
  /** 最近一次判分；最近一次是「看答案」时为 null。 */
  score: number | null;
  createdAt: string;
  attemptId: string;
  mode: "CLOZE" | "FREE";
  state: "NEW" | "LEARNING" | "MASTERED";
  dueAt: string;
  wrongCount: number;
  annotations: Array<{
    key: string;
    tag: string;
    message: string | null;
    disputed: boolean;
  }>;
}

/** 今日启动台快照；correctRate 供适配层补齐 todaySchema，页面可忽略。 */
export interface LocalTodaySnapshot {
  dueCount: number;
  doneCount: number;
  goal: number;
  streakDays: number;
  correctRate: number;
}

/** 统计页快照；correctRate / byMode 供适配层投影 /analytics/accuracy，页面可忽略。 */
export interface LocalStatsSnapshot {
  streakDays: number;
  totalAttempts: number;
  averageScore: number;
  /** 最近 14 天每日完成数，旧 → 新。 */
  daily14: number[];
  errorTags: Array<{ tag: string; count: number }>;
  recent: Array<{
    sentence: PracticeSentence;
    mode: string;
    score: number | null;
    createdAt: string;
  }>;
  correctRate: number;
  byMode: Array<{ mode: "CLOZE" | "FREE"; attempts: number; correctRate: number }>;
}

/** 同步键值存储；localStorage 语义，值必须是字符串（JSON 序列化由引擎负责）。 */
export interface EngineKeyValueStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/** 异步对象存储；按字符串主键存取，实现必须支持结构化克隆友好的普通对象。 */
export interface EngineRecordStore<T> {
  get(key: string): Promise<T | null>;
  put(key: string, value: T): Promise<void>;
  remove(key: string): Promise<void>;
  getAll(): Promise<T[]>;
  clear(): Promise<void>;
}

/**
 * 引擎存储后端。默认实现走 IndexedDB + localStorage；
 * 测试注入 createMemoryEngineStorage() 的内存实现（jsdom 没有 IndexedDB）。
 */
export interface EngineStorage {
  kv: EngineKeyValueStore;
  attempts(): Promise<EngineRecordStore<StoredAttempt>>;
  reviews(): Promise<EngineRecordStore<StoredReview>>;
  disputes(): Promise<EngineRecordStore<StoredDispute>>;
  bankCache(): Promise<EngineRecordStore<CachedBank>>;
}
