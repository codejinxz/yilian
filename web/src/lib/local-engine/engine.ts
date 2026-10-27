/**
 * 本地练习引擎的核心实现。
 *
 * 职责边界（AGENTS.md「本地优先」）：游客与登录用户在服务端练习切片（#8）落地前，
 * 拉题、判分、排程、降载、聚合统计都发生在本机；记录只落本机存储。
 * 判分复用 features/practice/grading 的纯函数；排程与降载规则与原型 finish() 语义一致：
 * - score<100（或看答案）→ LEARNING、明天到期；
 * - score=100 且原 LEARNING → 间隔 min(7, 间隔+2)，间隔≥5 归档 MASTERED；
 * - 新题全对保持 NEW（错才入队）；
 * - 到期>8 进入降载：只推最紧急 5 句，其余可顺延。
 */

import { z } from "zod";
import { gradeCloze, gradeFree, tokenizeAnswer } from "@/features/practice/grading";
import { ApiError } from "@/lib/api-error";
import { localDateString } from "@/lib/format";
import { bankPageSchema, versionedSentencesSchema } from "@/lib/schemas/content";
import {
  type AttemptRequest,
  type AttemptResponse,
  attemptRequestSchema,
  type GradingResult,
  type PracticeSentence,
  type Review,
} from "@/lib/schemas/practice";
import { createIndexedDbEngineStorage } from "./indexeddb-storage";
import type {
  CachedBank,
  EngineRecordStore,
  EngineStorage,
  LocalPlacementState,
  LocalReviewState,
  LocalStatsSnapshot,
  LocalTodaySnapshot,
  LocalWrongQuestionRow,
  StoredAttempt,
  StoredDispute,
  StoredReview,
} from "./types";

/** 一天的毫秒数；排程「明天 / N 天后」与原型的天计数语义一致。 */
const DAY_MS = 86_400_000;
/** 到期数超过该值进入降载模式（原型 renderReview 的 virtualDue > 8）。 */
const OVERLOAD_DUE_THRESHOLD = 8;
/** 降载时保留推送的最紧急卡数（原型只推 5 句）。 */
const OVERLOAD_KEEP_TOP = 5;
/** 学习中卡片的最大间隔天数（原型 Math.min(7, due + 2)）。 */
const MASTER_INTERVAL_CAP = 7;
/** 间隔达到该天数即归档（原型 due >= 5 → mastered）。 */
const MASTER_THRESHOLD = 5;
/** 每日目标的缺省值，与原型 store.get('yl-goal', 3) 一致。 */
const DEFAULT_DAILY_GOAL = 3;

const DEVICE_ID_KEY = "yilian.device-id";
const PLACEMENT_KEY = "yilian.placement.v1";
const SETTINGS_KEY = "yilian.settings.v1";

const BANK_LIST_TIMEOUT_MS = 10_000;

let storageOverride: EngineStorage | null = null;

/**
 * 注入存储后端；仅供单元测试使用（jsdom 没有 IndexedDB），传 null 恢复默认实现。
 * @param storage 内存存储后端，或 null 恢复默认
 */
export function setEngineStorageForTests(storage: EngineStorage | null): void {
  storageOverride = storage;
}

/**
 * 解析当前存储后端；IndexedDB 不可用且无注入时抛错，让所有引擎函数统一 reject。
 * @returns 存储后端
 */
function resolveStorage(): EngineStorage {
  if (storageOverride) {
    return storageOverride;
  }
  if (typeof indexedDB === "undefined") {
    throw new Error("当前环境不支持 IndexedDB，本地练习数据不可用");
  }
  return createIndexedDbEngineStorage();
}

/** @returns 答题记录 store */
function attemptsStore(): Promise<EngineRecordStore<StoredAttempt>> {
  return resolveStorage().attempts();
}

/** @returns 复习卡 store */
function reviewsStore(): Promise<EngineRecordStore<StoredReview>> {
  return resolveStorage().reviews();
}

/** @returns 「不认同」标记 store */
function disputesStore(): Promise<EngineRecordStore<StoredDispute>> {
  return resolveStorage().disputes();
}

/** @returns 题库缓存 store */
function bankCacheStore(): Promise<EngineRecordStore<CachedBank>> {
  return resolveStorage().bankCache();
}

/** @returns 全部答题记录 */
async function allAttempts(): Promise<StoredAttempt[]> {
  return (await attemptsStore()).getAll();
}

/** @returns 全部复习卡 */
async function allReviews(): Promise<StoredReview[]> {
  return (await reviewsStore()).getAll();
}

/** @returns 全部「不认同 / 申诉」标记 */
async function allDisputes(): Promise<StoredDispute[]> {
  return (await disputesStore()).getAll();
}

/**
 * 生成 UUID；crypto 不可用时退回随机串，仅用于本地 id，不承担安全性。
 * @returns UUID 字符串
 */
function randomUuid(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `local-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

/**
 * 计算本地时区的 YYYY-MM-DD 日键；「今天」的日界与原型一致用浏览器本地时区。
 * @param date 时间点
 * @returns 日键
 */
function dayKey(date: Date): string {
  return localDateString(date);
}

/**
 * 生成设备号；首次调用时生成并持久化，之后保持稳定。
 * @returns 设备 UUID
 */
export function getDeviceId(): string {
  const kv = resolveStorage().kv;
  const existing = kv.getItem(DEVICE_ID_KEY);
  if (existing) {
    return existing;
  }
  const generated = randomUuid();
  kv.setItem(DEVICE_ID_KEY, generated);
  return generated;
}

/**
 * 读取定级状态；没有记录或数据损坏时返回 null，由页面重新定级。
 * @returns 定级状态或 null
 */
export function loadPlacementState(): LocalPlacementState | null {
  const raw = resolveStorage().kv.getItem(PLACEMENT_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as Partial<LocalPlacementState>;
    if (typeof parsed.placed !== "boolean") {
      return null;
    }
    return {
      placed: parsed.placed,
      startLevel: typeof parsed.startLevel === "string" ? parsed.startLevel : null,
      missedTags: Array.isArray(parsed.missedTags)
        ? parsed.missedTags.filter((tag): tag is string => typeof tag === "string")
        : [],
    };
  } catch {
    return null;
  }
}

/**
 * 保存定级状态；定级流程结束时调用一次。
 * @param state 完整定级状态
 */
export function savePlacementState(state: LocalPlacementState): void {
  resolveStorage().kv.setItem(PLACEMENT_KEY, JSON.stringify(state));
}

/**
 * 清空定级状态；「重置本地数据」与重新定级入口使用。
 */
export function resetPlacementState(): void {
  resolveStorage().kv.removeItem(PLACEMENT_KEY);
}

/**
 * 读取每日目标；与原型 yl-goal 语义一致，缺省 3。
 * @returns 每日目标句数
 */
function getDailyGoal(): number {
  const raw = resolveStorage().kv.getItem(SETTINGS_KEY);
  if (!raw) {
    return DEFAULT_DAILY_GOAL;
  }
  try {
    const parsed = JSON.parse(raw) as { dailyGoal?: unknown };
    const goal = parsed.dailyGoal;
    return typeof goal === "number" && Number.isFinite(goal) && goal >= 1
      ? Math.floor(goal)
      : DEFAULT_DAILY_GOAL;
  } catch {
    return DEFAULT_DAILY_GOAL;
  }
}

/**
 * 保存每日目标；定级完成页与设置入口使用。
 * @param goal 目标句数；小于 1 或非法时回退缺省值
 */
export function setDailyGoal(goal: number): void {
  const safe = Number.isFinite(goal) && goal >= 1 ? Math.floor(goal) : DEFAULT_DAILY_GOAL;
  resolveStorage().kv.setItem(SETTINGS_KEY, JSON.stringify({ dailyGoal: safe }));
}

const bankEnvelopeSchema = z.object({
  code: z.number(),
  message: z.string(),
  data: z.unknown().optional(),
});

/**
 * 匿名请求公开题库接口并解包统一信封。
 * 引擎刻意不走 apiRequest：banks 是匿名可访问的 GET，独立 fetch 避免本地路由递归，
 * 也让「拉取失败 → 保留旧缓存」的降级完全在引擎内部闭环。
 * @param path `/api` 之后的路径（含查询串）
 * @returns 信封中的 data
 * @throws ApiError 网络失败、超时、HTTP 或业务码失败
 */
async function fetchBankJson(path: string): Promise<unknown> {
  const controller = new AbortController();
  const timeoutId = setTimeout(
    () => controller.abort(new DOMException("请求超时", "TimeoutError")),
    BANK_LIST_TIMEOUT_MS,
  );
  try {
    const response = await fetch(new URL(`/api${path}`, window.location.origin), {
      headers: { Accept: "application/json" },
      credentials: "include",
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new ApiError(
        response.status,
        response.status,
        `题库拉取失败（HTTP ${response.status}）`,
      );
    }
    const parsed = bankEnvelopeSchema.safeParse(await response.json());
    if (!parsed.success) {
      throw new ApiError(response.status, -1, "题库响应结构异常");
    }
    if (parsed.data.code !== 0) {
      throw new ApiError(response.status, parsed.data.code, parsed.data.message || "题库拉取失败");
    }
    return parsed.data.data;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    if (error instanceof DOMException) {
      throw new ApiError(0, -1, "题库拉取超时，请稍后重试");
    }
    throw new ApiError(0, -1, "网络连接失败，无法刷新题库");
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * 判断版本化句子条目是否可直接用于本地练习；deleted/草稿或缺语块数据的条目一律丢弃。
 * @param item 版本化句子
 * @returns 是否可练
 */
function isPracticableSentence(item: {
  deleted?: boolean;
  status?: string;
  zh?: string;
  ref?: string;
  chunks?: unknown[];
  blank?: unknown[];
  tags?: unknown[];
}): boolean {
  return (
    item.deleted !== true &&
    item.status === "PUBLISHED" &&
    typeof item.zh === "string" &&
    typeof item.ref === "string" &&
    Array.isArray(item.chunks) &&
    item.chunks.length > 0 &&
    Array.isArray(item.blank) &&
    Array.isArray(item.tags)
  );
}

/**
 * 匿名拉取公开题库并整体重建本地缓存。
 * 全部请求成功才写入；任一步失败抛错并保留旧缓存，保证离线可继续用旧题练习。
 * 服务端可达但没有任何公开题库时同样保留旧缓存，避免把可用题库清成空态。
 * @throws ApiError 拉取或解析失败
 */
export async function refreshBankCache(): Promise<void> {
  // 容量上限：单次只拉前 100 个公开题库、每库前 200 句；超限部分静默缺句。
  // 当前演示题库远小于上限，扩容与「未拉全」提示留到内容量成为真实问题时再做。
  const pageData = await fetchBankJson("/banks?page=1&size=100&status=PUBLISHED");
  const page = bankPageSchema.safeParse(pageData);
  if (!page.success) {
    throw new ApiError(0, -1, "题库列表结构异常");
  }
  const banks = page.data.items;
  if (banks.length === 0) {
    return;
  }
  const entries: CachedBank[] = [];
  for (const bank of banks) {
    const data = await fetchBankJson(
      `/banks/${encodeURIComponent(bank.id)}/sentences?sinceVersion=0&limit=200`,
    );
    // 墓碑行（deleted=true）按 API §1.7 显式输出 null 字段，会让共享 schema 的整包解析失败；
    // 引擎走 sinceVersion=0 全量重建，墓碑没有增量价值，解析前直接丢弃。
    const rawItems = (data as { items?: Array<Record<string, unknown>> | undefined }).items;
    const pruned =
      Array.isArray(rawItems) && rawItems.some((item) => item.deleted === true)
        ? { ...(data as object), items: rawItems.filter((item) => item.deleted !== true) }
        : data;
    const payload = versionedSentencesSchema.safeParse(pruned);
    if (!payload.success) {
      throw new ApiError(0, -1, "题库句子结构异常");
    }
    const sentences = payload.data.items.filter((item) =>
      isPracticableSentence(item as Partial<PracticeSentence>),
    ) as PracticeSentence[];
    entries.push({
      bankId: bank.id,
      sentences,
      fetchedAt: new Date().toISOString(),
    });
  }
  const store = await bankCacheStore();
  await store.clear();
  for (const entry of entries) {
    await store.put(entry.bankId, entry);
  }
}

/**
 * 读取本地题库缓存。
 * @param bankId 指定题库；省略时返回全部题库句子的并集
 * @returns 可练句子；无缓存时为空数组
 */
export async function getCachedSentences(bankId?: string): Promise<PracticeSentence[]> {
  const store = await bankCacheStore();
  if (bankId !== undefined) {
    return (await store.get(bankId))?.sentences ?? [];
  }
  const banks = (await store.getAll()).sort((a, b) => a.bankId.localeCompare(b.bankId));
  return banks.flatMap((bank) => bank.sentences);
}

/**
 * 取定级用的 3 道题：优先本地缓存；无缓存时尝试匿名拉取一次公开题库；仍无数据则 reject。
 * 选取覆盖题库首、中、尾的 3 句，近似原型的固定 3 题难度跨度。
 * @returns 3 道定级题（题库不足 3 句时返回全部）
 * @throws Error 本地与远端都没有可用题目
 */
export async function getPlacementSentences(): Promise<PracticeSentence[]> {
  let sentences = await getCachedSentences();
  if (sentences.length === 0) {
    try {
      await refreshBankCache();
    } catch {
      // 离线或服务端不可用：保留空结果，由调用方展示重试态。
    }
    sentences = await getCachedSentences();
  }
  if (sentences.length === 0) {
    throw new Error("本地还没有题目缓存，请联网后重试");
  }
  if (sentences.length <= 3) {
    return sentences;
  }
  const mid = Math.floor(sentences.length / 2);
  return [
    sentences[0] as PracticeSentence,
    sentences[mid] as PracticeSentence,
    sentences[sentences.length - 1] as PracticeSentence,
  ];
}

/**
 * 拉取下一题：到期卡优先（dueAt 升序取最紧急），否则按题库顺序取未学新题。
 * 学习中但未到期的卡与已归档卡不进入题库顺序，由复习队列按到期时间召回。
 *
 * <p>筛选语义：显式传 tag / mode 时到期召回同样受约束（与 mock 契约及页面模式切换一致）。
 * 复习卡是「每句单卡」，card.mode 记录最近一次练习的模式——切换模式练习会改写它，
 * 因此 mode 筛选的含义是「只召回以该模式练过的到期卡」，不是独立的双模式排程维度。
 *
 * @param params 题库、考点、模式与排除题
 * @returns 下一题；没有可练题时返回 null（页面显示空态）
 */
export async function pickNext(params?: {
  bankId?: string;
  tag?: string;
  mode?: "CLOZE" | "FREE";
  excludeIds?: string[];
}): Promise<PracticeSentence | null> {
  const sentences = await getCachedSentences(params?.bankId);
  if (sentences.length === 0) {
    return null;
  }
  const cards = new Map((await allReviews()).map((card) => [card.sentenceId, card]));
  const now = Date.now();
  const excluded = new Set(params?.excludeIds ?? []);
  const dueCards = [...cards.values()]
    .filter((card) => card.state === "LEARNING" && new Date(card.dueAt).getTime() <= now)
    .sort((a, b) => a.dueAt.localeCompare(b.dueAt));
  for (const card of dueCards) {
    if (excluded.has(card.sentenceId)) {
      continue;
    }
    const sentence = sentences.find((item) => item.id === card.sentenceId);
    if (!sentence) {
      continue;
    }
    if (params?.tag && !sentence.tags.includes(params.tag)) {
      continue;
    }
    if (params?.mode && card.mode !== params.mode) {
      continue;
    }
    return sentence;
  }
  for (const sentence of sentences) {
    if (excluded.has(sentence.id)) {
      continue;
    }
    if (params?.tag && !sentence.tags.includes(params.tag)) {
      continue;
    }
    const card = cards.get(sentence.id);
    if (card && card.state !== "NEW") {
      continue;
    }
    return sentence;
  }
  return null;
}

/**
 * 计算 mock 同款四档评分（DATA-MODEL §8.2 派生规则），存入复习卡便于后续接入 FSRS。
 * @param score 判分；REVEAL 为 null
 * @param hintsUsed 提示次数
 * @param durationMs 作答耗时
 * @param kind 提交类型
 * @returns 1–4
 */
function deriveRating(
  score: number | null,
  hintsUsed: number,
  durationMs: number,
  kind: "FIRST" | "RETRY" | "REVEAL",
): number {
  if (kind === "REVEAL" || score === null || score < 60) {
    return 1;
  }
  if (score < 100) {
    return 2;
  }
  if (hintsUsed > 0) {
    return 3;
  }
  return durationMs <= 60_000 ? 4 : 3;
}

/**
 * 构造「看答案」的判分结果：不计分，但按挖空总数给出占位 totalCount。
 * @param sentence 题目
 * @returns 判分结果
 */
function revealGrading(sentence: PracticeSentence): GradingResult {
  const total = sentence.blank.reduce((sum, chunkIndex) => {
    const chunk = sentence.chunks.find((item) => item.index === chunkIndex);
    return sum + tokenizeAnswer(chunk?.answers[0] ?? "").length;
  }, 0);
  return {
    schemaVersion: 1,
    score: null,
    maxScore: 100,
    hitCount: 0,
    totalCount: total,
    annotations: [],
    summary: "已看答案，记为学习中",
  };
}

/**
 * 本地判分：填空走 gradeCloze、整句走 gradeFree、看答案不计分；与 mock 判分完全同规则。
 * @param sentence 题目
 * @param input 已校验的 attempt 请求
 * @returns 判分结果
 */
function gradeAttempt(sentence: PracticeSentence, input: AttemptRequest): GradingResult {
  if (input.kind === "REVEAL") {
    return revealGrading(sentence);
  }
  if (input.answer.text !== undefined && sentence.blank.length === 0) {
    return gradeFree({ sentence: sentence.zh, ref: sentence.ref, text: input.answer.text });
  }
  const slotMap = new Map((input.answer.slots ?? []).map((slot) => [slot.chunkIndex, slot.value]));
  const answers = sentence.blank.map((chunkIndex) => slotMap.get(chunkIndex) ?? "");
  return gradeCloze({
    sentence: sentence.zh,
    chunks: sentence.chunks,
    blank: sentence.blank,
    answers,
  });
}

/**
 * 按原型 finish() 的排程规则推进复习卡；RETRY 不排程。
 * @param card 现有复习卡，可能为 null
 * @param input 已校验的 attempt 请求
 * @param score 判分；REVEAL 为 null
 * @param rating 四档评分
 * @param nowMs 当前时间戳
 * @param nowIso 当前 ISO 时间
 * @returns 更新后的卡；RETRY 且无卡、或新题全对时为 null
 */
function scheduleReview(
  card: StoredReview | null,
  input: AttemptRequest,
  score: number | null,
  rating: number,
  nowMs: number,
  nowIso: string,
): StoredReview | null {
  if (input.kind === "RETRY") {
    return card;
  }
  const failed = input.kind === "REVEAL" || score === null || score < 100;
  if (failed) {
    // 答错 / 看答案：置 LEARNING、明天到期；已归档卡同样回炉（原型 score<100 分支）。
    return {
      id: card?.id ?? `lr-${input.sentenceId}`,
      sentenceId: input.sentenceId,
      mode: input.mode,
      state: "LEARNING",
      fsrsState: "LEARNING",
      dueAt: new Date(nowMs + DAY_MS).toISOString(),
      stability: 2.4,
      difficulty: 5.3,
      reps: (card?.reps ?? 0) + 1,
      lapses: (card?.lapses ?? 0) + 1,
      scheduledDays: 1,
      rating,
      lastRating: rating,
      lastReviewedAt: nowIso,
      updatedAt: nowIso,
    };
  }
  if (card && card.state === "LEARNING") {
    // 全对且在学习中：间隔 +2（封顶 7），间隔 ≥5 归档——「两全对 → 归档」的来源。
    const interval = Math.min(MASTER_INTERVAL_CAP, (card.scheduledDays || 0) + 2);
    const mastered = interval >= MASTER_THRESHOLD;
    return {
      ...card,
      mode: input.mode,
      state: mastered ? "MASTERED" : "LEARNING",
      fsrsState: mastered ? "REVIEW" : "LEARNING",
      dueAt: new Date(nowMs + interval * DAY_MS).toISOString(),
      stability: mastered ? 40 : 3.2,
      difficulty: 5.1,
      reps: card.reps + 1,
      scheduledDays: interval,
      rating,
      lastRating: rating,
      lastReviewedAt: nowIso,
      updatedAt: nowIso,
    };
  }
  if (card && card.state === "MASTERED") {
    // 已归档卡全对：保持归档，只刷新复习痕迹。
    return {
      ...card,
      reps: card.reps + 1,
      rating,
      lastRating: rating,
      lastReviewedAt: nowIso,
      updatedAt: nowIso,
    };
  }
  // 新题全对保持 NEW（错才入队）；已有的 NEW 卡同样不动。
  return card ?? null;
}

/**
 * 构造提交响应；字段与 attemptResponseSchema 严格对齐，grading 元数据与 mock 同版本。
 * @param input 已校验的 attempt 请求
 * @param sentence 题目
 * @param result 判分结果
 * @param review 复习卡
 * @param replayed 是否幂等重放
 * @returns AttemptResponse
 */
function buildAttemptResponse(
  input: AttemptRequest,
  sentence: PracticeSentence,
  result: GradingResult,
  review: StoredReview | null,
  replayed: boolean,
): AttemptResponse {
  return {
    attemptId: input.attemptId,
    sentenceId: input.sentenceId,
    sentenceVersionId: input.sentenceVersionId,
    mode: input.mode,
    kind: input.kind,
    score: result.score,
    maxScore: 100,
    result,
    grading: {
      id: `local-${randomUuid()}`,
      sequence: 1,
      grader: "LOCAL",
      normalizerVersion: "norm-1",
      rubricVersion: "rubric-1",
      createdAt: new Date().toISOString(),
    },
    review: review ?? null,
    reference: { ref: sentence.ref, note: sentence.note ?? null },
    nextAction: {
      retryRequired: result.score !== null && result.score < 100 && input.kind === "FIRST",
      canReveal: input.kind !== "REVEAL",
    },
    replayed,
  };
}

/**
 * 生成记录时间戳：同一毫秒内多次提交时强制递增 1ms，
 * 保证「最近一次作答」按 createdAt 比较是确定性的（IndexedDB 没有插入顺序可依赖）。
 * @param nowMs 当前时间戳
 * @returns 唯一且单调递增的时间戳毫秒值
 */
let lastRecordMs = 0;
function nextRecordMs(nowMs: number): number {
  lastRecordMs = Math.max(nowMs, lastRecordMs + 1);
  return lastRecordMs;
}

/**
 * 提交一次本地作答：判分 → 排程 → 落记录，三步都在本机完成。
 * 以 attemptId 幂等：重复提交返回原结果并标记 replayed，不产生新记录。
 * @param req attempt 请求
 * @returns 判分结果、批注、参考译文与复习状态
 * @throws ApiError 请求不合法、题目不在本地题库、REVEAL/RETRY 约束不满足
 */
export async function submitLocalAttempt(req: AttemptRequest): Promise<AttemptResponse> {
  const store = await attemptsStore();
  const parsed = attemptRequestSchema.safeParse(req);
  if (!parsed.success) {
    throw new ApiError(400, 40000, parsed.error.issues[0]?.message ?? "请求参数不合法");
  }
  const input = parsed.data;
  // 幂等采用 check-then-put：同 attemptId 理论上存在并发双提交窗口（本地单用户
  // 单标签页下概率可忽略；未来若引入多标签并发，应把查重与写入并进同一事务）。
  const existing = await store.get(input.attemptId);
  if (existing) {
    return { ...existing.response, replayed: true };
  }
  const sentence = (await getCachedSentences()).find((item) => item.id === input.sentenceId);
  if (!sentence) {
    throw new ApiError(404, 40402, "题目不在本地题库中，请联网刷新题库后重试");
  }
  if (input.kind === "REVEAL" && input.answer.revealed !== true) {
    throw new ApiError(400, 40000, "看答案必须标记 revealed");
  }
  if (input.kind === "RETRY") {
    const original = input.retryOfId ? await store.get(input.retryOfId) : null;
    if (!original || original.sentenceId !== input.sentenceId || original.mode !== input.mode) {
      throw new ApiError(400, 40000, "改错记录必须指向同一题、同一模式的原始提交");
    }
  }
  const nowMs = nextRecordMs(Date.now());
  const nowIso = new Date(nowMs).toISOString();
  const result = gradeAttempt(sentence, input);
  const rating = deriveRating(result.score, input.hintsUsed, input.durationMs, input.kind);
  const reviewStore = await reviewsStore();
  const review = scheduleReview(
    await reviewStore.get(input.sentenceId),
    input,
    result.score,
    rating,
    nowMs,
    nowIso,
  );
  const response = buildAttemptResponse(input, sentence, result, review, false);
  // 先落答题记录再更新复习卡：中断时宁可复习卡滞后，也不丢判分记录（append-only）。
  await store.put(input.attemptId, {
    attemptId: input.attemptId,
    sentenceId: input.sentenceId,
    mode: input.mode,
    kind: input.kind,
    score: result.score,
    request: input,
    response,
    createdAt: nowIso,
  });
  if (review) {
    await reviewStore.put(review.sentenceId, review);
  }
  return response;
}

/**
 * 读取本地答题结果；页面刷新后恢复批改页使用。
 * @param attemptId attempt UUID
 * @returns 判分结果；不存在时为 null
 */
export async function getAttemptLocal(attemptId: string): Promise<AttemptResponse | null> {
  const record = await (await attemptsStore()).get(attemptId);
  return record?.response ?? null;
}

/**
 * 计算连续练习天数：从今天往回数，遇到没有记录的日期即停（与原型 calcStreak 一致）。
 * @param attempts 答题记录
 * @returns 连续天数
 */
function calcStreak(attempts: StoredAttempt[]): number {
  const keys = new Set(attempts.map((attempt) => dayKey(new Date(attempt.createdAt))));
  let streak = 0;
  for (let offset = 0; offset < 366; offset += 1) {
    if (keys.has(dayKey(new Date(Date.now() - offset * DAY_MS)))) {
      streak += 1;
    } else {
      break;
    }
  }
  return streak;
}

/**
 * 今日启动台快照：到期数、今日已练、目标与连续天数。
 * @returns 快照
 */
export async function getTodaySnapshot(): Promise<LocalTodaySnapshot> {
  const nowMs = Date.now();
  const cards = await allReviews();
  const dueCount = cards.filter(
    (card) => card.state === "LEARNING" && new Date(card.dueAt).getTime() <= nowMs,
  ).length;
  const attempts = await allAttempts();
  const today = dayKey(new Date(nowMs));
  const todayAttempts = attempts.filter((attempt) => dayKey(new Date(attempt.createdAt)) === today);
  const scored = todayAttempts.filter((attempt) => attempt.score !== null);
  const correct = scored.filter((attempt) => attempt.score === 100).length;
  return {
    dueCount,
    doneCount: todayAttempts.length,
    goal: getDailyGoal(),
    streakDays: calcStreak(attempts),
    correctRate: scored.length === 0 ? 0 : Math.round((correct / scored.length) * 1000) / 10,
  };
}

/**
 * 复习队列全量：到期/学习中/已掌握汇总与按 dueAt 升序的卡片行。
 * 题库缓存里已不存在的句子（例如题库下架）不返回。
 * @returns 汇总与行
 */
export async function getReviewQueue(): Promise<{
  summary: { due: number; learning: number; mastered: number };
  rows: Array<{ sentence: PracticeSentence; review: LocalReviewState }>;
}> {
  const cards = (await allReviews()).sort((a, b) => a.dueAt.localeCompare(b.dueAt));
  const nowMs = Date.now();
  const summary = {
    due: cards.filter(
      (card) => card.state === "LEARNING" && new Date(card.dueAt).getTime() <= nowMs,
    ).length,
    learning: cards.filter((card) => card.state === "LEARNING").length,
    mastered: cards.filter((card) => card.state === "MASTERED").length,
  };
  const sentences = new Map((await getCachedSentences()).map((s) => [s.id, s]));
  const rows = cards.flatMap((card) => {
    const sentence = sentences.get(card.sentenceId);
    // StoredReview 是 LocalReviewState 的超集，页面只消费子集字段。
    return sentence ? [{ sentence, review: card as LocalReviewState }] : [];
  });
  return { summary, rows };
}

/**
 * 错题列表：按句子取最近一次判分，保留批注明细与「不认同」标记。
 * 最近一次全对（score≥100）的句子视为已修复；最近一次为看答案（score=null）的句子保留。
 * @returns 错题行，按最近作答时间倒序
 */
export async function getWrongQuestions(): Promise<LocalWrongQuestionRow[]> {
  const attempts = (await allAttempts()).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const latestBySentence = new Map<string, StoredAttempt>();
  for (const attempt of attempts) {
    if (!latestBySentence.has(attempt.sentenceId)) {
      latestBySentence.set(attempt.sentenceId, attempt);
    }
  }
  const disputed = new Set(
    (await allDisputes()).filter((marker) => marker.kind === "DISPUTE").map((marker) => marker.key),
  );
  const cards = new Map((await allReviews()).map((card) => [card.sentenceId, card]));
  const sentences = new Map((await getCachedSentences()).map((s) => [s.id, s]));
  const rows: LocalWrongQuestionRow[] = [];
  for (const [sentenceId, attempt] of latestBySentence) {
    if (attempt.score !== null && attempt.score >= 100) {
      continue;
    }
    const sentence = sentences.get(sentenceId);
    if (!sentence) {
      continue;
    }
    const card = cards.get(sentenceId);
    rows.push({
      sentence,
      score: attempt.score,
      createdAt: attempt.createdAt,
      attemptId: attempt.attemptId,
      mode: attempt.mode,
      state: card?.state ?? "NEW",
      dueAt: card?.dueAt ?? attempt.createdAt,
      wrongCount: attempts.filter(
        (item) => item.sentenceId === sentenceId && item.score !== null && item.score < 100,
      ).length,
      annotations: attempt.response.result.annotations
        .filter((annotation) => !annotation.correct)
        .map((annotation) => ({
          key: annotation.key,
          tag: annotation.tag,
          message: annotation.message,
          disputed: disputed.has(`${attempt.attemptId}:${annotation.key}`),
        })),
    });
  }
  rows.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return rows;
}

/**
 * 统计页快照：连续天数、累计、平均分、14 天趋势、考点分布与最近记录。
 * 考点统计已排除被「不认同」的批注；看答案（score=null）不计入正确率与平均分。
 * @returns 快照
 */
export async function getStatsSnapshot(): Promise<LocalStatsSnapshot> {
  const attempts = (await allAttempts()).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const scored = attempts.filter((attempt) => attempt.score !== null);
  const averageScore =
    scored.length === 0
      ? 0
      : Math.round(
          (scored.reduce((sum, attempt) => sum + (attempt.score ?? 0), 0) / scored.length) * 10,
        ) / 10;
  const correct = scored.filter((attempt) => attempt.score === 100).length;
  const correctRate = scored.length === 0 ? 0 : Math.round((correct / scored.length) * 1000) / 10;
  const daily14: number[] = [];
  for (let offset = 13; offset >= 0; offset -= 1) {
    const key = dayKey(new Date(Date.now() - offset * DAY_MS));
    daily14.push(attempts.filter((attempt) => dayKey(new Date(attempt.createdAt)) === key).length);
  }
  const disputed = new Set(
    (await allDisputes()).filter((marker) => marker.kind === "DISPUTE").map((marker) => marker.key),
  );
  const tagCounts = new Map<string, number>();
  for (const attempt of attempts) {
    for (const annotation of attempt.response.result.annotations) {
      if (annotation.correct) {
        continue;
      }
      if (disputed.has(`${attempt.attemptId}:${annotation.key}`)) {
        continue;
      }
      tagCounts.set(annotation.tag, (tagCounts.get(annotation.tag) ?? 0) + 1);
    }
  }
  const errorTags = [...tagCounts.entries()]
    .map(([tag, count]) => ({ tag, count }))
    .sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
  const sentences = new Map((await getCachedSentences()).map((s) => [s.id, s]));
  const recent = attempts.slice(0, 5).flatMap((attempt) => {
    const sentence = sentences.get(attempt.sentenceId);
    return sentence
      ? [{ sentence, mode: attempt.mode, score: attempt.score, createdAt: attempt.createdAt }]
      : [];
  });
  const byMode = (["CLOZE", "FREE"] as const).map((mode) => {
    const modeScored = scored.filter((attempt) => attempt.mode === mode);
    const modeCorrect = modeScored.filter((attempt) => attempt.score === 100).length;
    return {
      mode,
      attempts: modeScored.length,
      correctRate:
        modeScored.length === 0 ? 0 : Math.round((modeCorrect / modeScored.length) * 1000) / 10,
    };
  });
  return {
    streakDays: calcStreak(attempts),
    totalAttempts: attempts.length,
    averageScore,
    daily14,
    errorTags,
    recent,
    correctRate,
    byMode,
  };
}

/**
 * 降载状态：到期数超过阈值（原型 8）即激活，页面据此只推最紧急几句并提供顺延入口。
 * @returns 是否降载与当前到期数
 */
export async function getOverloadState(): Promise<{ active: boolean; dueCount: number }> {
  const nowMs = Date.now();
  const dueCount = (await allReviews()).filter(
    (card) => card.state === "LEARNING" && new Date(card.dueAt).getTime() <= nowMs,
  ).length;
  return { active: dueCount > OVERLOAD_DUE_THRESHOLD, dueCount };
}

/**
 * 顺延到期卡：保留最紧急 5 张（dueAt 升序），其余推到 days 天后。
 * 与原型「顺延其余」一致——正在推送的 5 张不顺延，避免用户正在练的卡被移走。
 * @param days 顺延天数；小于 1 按 1 处理
 * @returns 实际顺延的张数
 */
export async function postponeOverdue(days: number): Promise<number> {
  const safeDays = Math.max(1, Math.floor(days));
  const store = await reviewsStore();
  const nowMs = Date.now();
  const nowIso = new Date(nowMs).toISOString();
  const dueCards = (await store.getAll())
    .filter((card) => card.state === "LEARNING" && new Date(card.dueAt).getTime() <= nowMs)
    .sort((a, b) => a.dueAt.localeCompare(b.dueAt));
  const toPostpone = dueCards.slice(OVERLOAD_KEEP_TOP);
  for (const card of toPostpone) {
    await store.put(card.sentenceId, {
      ...card,
      dueAt: new Date(nowMs + safeDays * DAY_MS).toISOString(),
      scheduledDays: safeDays,
      updatedAt: nowIso,
    });
  }
  return toPostpone.length;
}

/**
 * 记录批注标记；重复操作覆盖同一稳定键（append-only 语义：不修改原始判分记录）。
 * @param attemptId attempt UUID
 * @param annotationKey 批注稳定键
 * @param kind 申诉或「不认同」
 * @param status 目标状态
 */
async function recordAnnotationMarker(
  attemptId: string,
  annotationKey: string,
  kind: "APPEAL" | "DISPUTE",
  status: "PENDING" | "ACCEPTED",
): Promise<void> {
  const store = await disputesStore();
  const key = `${attemptId}:${annotationKey}`;
  const existing = await store.get(key);
  const marker: StoredDispute = {
    key,
    attemptId,
    annotationKey,
    kind,
    status,
    createdAt: existing?.createdAt ?? new Date().toISOString(),
  };
  await store.put(key, marker);
}

/**
 * 「不认同」一条批注：该条立即排除出错题与考点统计，原始判分记录不变。
 * @param attemptId attempt UUID
 * @param annotationKey 批注稳定键
 */
export async function setAnnotationDispute(
  attemptId: string,
  annotationKey: string,
): Promise<void> {
  await recordAnnotationMarker(attemptId, annotationKey, "DISPUTE", "ACCEPTED");
}

/**
 * 「我觉得也对」申诉：本地记为 PENDING，不改判分、不影响统计，等待后续人工/模型裁决。
 * @param attemptId attempt UUID
 * @param annotationKey 批注稳定键
 */
export async function setAnnotationAppeal(attemptId: string, annotationKey: string): Promise<void> {
  await recordAnnotationMarker(attemptId, annotationKey, "APPEAL", "PENDING");
}

/**
 * 把复习卡重置为 NEW（从头练）；保留答题历史，只重置调度状态，语义与 mock/服务端一致。
 * @param reviewId 复习卡 id（`lr-{sentenceId}`）
 * @returns 重置后的卡；卡不存在时为 null
 */
export async function resetReviewState(reviewId: string): Promise<LocalReviewState | null> {
  const sentenceId = reviewId.startsWith("lr-") ? reviewId.slice(3) : reviewId;
  const store = await reviewsStore();
  const card = await store.get(sentenceId);
  if (!card) {
    return null;
  }
  const nowIso = new Date().toISOString();
  const reset: StoredReview = {
    ...card,
    state: "NEW",
    fsrsState: "NEW",
    dueAt: nowIso,
    stability: 0,
    difficulty: 0,
    reps: 0,
    lapses: 0,
    scheduledDays: 0,
    lastRating: null,
    lastReviewedAt: null,
    rating: null,
    updatedAt: nowIso,
  };
  await store.put(sentenceId, reset);
  return reset;
}

/**
 * 导出全部本地数据为可序列化 JSON（设置页「导出」入口）；含题库缓存便于完整备份。
 * @returns 导出对象
 */
export async function exportLocalData(): Promise<unknown> {
  const [attempts, reviews, disputes, banks] = await Promise.all([
    attemptsStore().then((store) => store.getAll()),
    reviewsStore().then((store) => store.getAll()),
    disputesStore().then((store) => store.getAll()),
    bankCacheStore().then((store) => store.getAll()),
  ]);
  return {
    version: 1,
    exportedAt: new Date().toISOString(),
    deviceId: getDeviceId(),
    placement: loadPlacementState(),
    dailyGoal: getDailyGoal(),
    attempts,
    reviews,
    disputes,
    bankCache: banks,
  };
}

/**
 * 清空本地练习数据：四类记录与题库缓存、定级与目标全部清除；设备号保留（非练习数据）。
 */
export async function clearLocalData(): Promise<void> {
  const storage = resolveStorage();
  await Promise.all([
    storage.attempts().then((store) => store.clear()),
    storage.reviews().then((store) => store.clear()),
    storage.disputes().then((store) => store.clear()),
    storage.bankCache().then((store) => store.clear()),
  ]);
  storage.kv.removeItem(PLACEMENT_KEY);
  storage.kv.removeItem(SETTINGS_KEY);
}

/**
 * 适配层专用：把存储中的复习卡投影为 API Review 契约（去掉 updatedAt）。
 *
 * <p>单键语义：复习卡按 sentenceId 一句一卡，card.mode 是「最近一次练习的模式」而非
 * 独立排程维度（换模式练习会改写它）。这与 DATA-MODEL 的 reviews 表和原型的每句一行
 * 一致；mock 层按 (用户, 句子, 模式) 三元组建卡属演示层偏差，#8 切回服务端时以本语义为准。
 *
 * @param card 本地复习卡
 * @returns API Review 形状（不含 updatedAt）
 */
export function reviewToApi(card: StoredReview): Review {
  const { updatedAt: _updatedAt, ...rest } = card;
  return rest;
}
