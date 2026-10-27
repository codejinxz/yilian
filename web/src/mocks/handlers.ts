import { gradeCloze, gradeFree, normalizeAnswer } from "@/features/practice/grading";
import { ApiError } from "@/lib/api-error";
import { localDateString } from "@/lib/format";
import { registerRequestSchema } from "@/lib/schemas/account";
import type { AdminUser, AuditLog } from "@/lib/schemas/admin";
import type { Accuracy, ErrorTags } from "@/lib/schemas/analytics";
import {
  type BankView,
  bankCreateRequestSchema,
  type ImportBatchView,
  type ImportRow,
  type ImportRowError,
  sentenceStatusRequestSchema,
} from "@/lib/schemas/content";
import {
  type AttemptResponse,
  attemptRequestSchema,
  type GradingResult,
  type PracticeSentence,
  type Review,
} from "@/lib/schemas/practice";
import {
  getMockDb,
  type MockAttempt,
  type MockDb,
  type MockImportBatch,
  type MockReview,
  type MockSentence,
  type MockUploadBatch,
  type MockUser,
  mockUlid,
  nextMockId,
  paginate,
  persistMockDb,
} from "./db";

const MOCK_DELAY_MS = 45;
const DEFAULT_PAGE_SIZE = 20;

/** 内容生命周期全集合；批量与编辑端点用它校验状态值。 */
const CONTENT_STATUSES = ["DRAFT", "PUBLISHED", "DISABLED", "RECYCLED", "ARCHIVED"] as const;
type ContentStatus = (typeof CONTENT_STATUSES)[number];

/**
 * mock 请求前的固定延迟，让 loading / pending 状态在演示中真实出现，但不拖慢测试。
 */
async function delay(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, MOCK_DELAY_MS));
}

/**
 * 抛出带 HTTP 状态与业务码的 mock 错误，由请求层统一转成 ApiError 展示。
 * @param status HTTP 状态
 * @param code 业务码
 * @param message 中文提示
 */
function fail(status: number, code: number, message: string): never {
  throw new ApiError(status, code, message);
}

/**
 * 当前 mock 登录用户；禁用或已删除账号视为未登录。
 * @param db mock 数据库
 * @returns 当前用户或 null
 */
function currentUser(db: MockDb): MockUser | null {
  if (!db.sessionUserId) {
    return null;
  }
  const user = db.users.find((item) => item.id === db.sessionUserId);
  if (!user || !user.enabled || user.deleted) {
    return null;
  }
  return user;
}

/** 游客回退用户的用户名；与 db.ts 种子保持一致。 */
const GUEST_USERNAME = "游客";

/**
 * 要求已登录；未登录时回退到种子「游客」演示用户，让 mock 模式下游客可刷全部练习页。
 * 登录/注册切换 sessionUserId，登出回到游客态；requireAdmin 仍会拒绝游客进入管理端。
 * @param db mock 数据库
 * @returns 当前用户（真实登录用户或游客）
 */
function requireUser(db: MockDb): MockUser {
  const user = currentUser(db);
  if (user) {
    return user;
  }
  const guest = db.users.find(
    (item) => item.username === GUEST_USERNAME && item.enabled && !item.deleted,
  );
  if (!guest) {
    fail(401, 40100, "请先登录");
  }
  return guest;
}

/**
 * 要求管理员；mock 也模拟服务端鉴权，前端隐藏入口不算权限。
 * @param db mock 数据库
 * @returns 当前管理员
 */
function requireAdmin(db: MockDb): MockUser {
  const user = requireUser(db);
  if (user.role !== "ADMIN") {
    fail(403, 40300, "没有访问权限");
  }
  return user;
}

/**
 * 当前用户的最小视图，不返回密码与设置。
 * @param user mock 用户
 * @returns API 用户结构
 */
function toCurrentUser(user: MockUser) {
  return {
    id: user.id,
    username: user.username,
    role: user.role,
    passwordChanged: true,
    // TODO(mock): 演示数据，真实接口落地后删除——会话补充邮箱与答题数，供壳层展示。
    email: user.email,
    attemptsCount: user.attemptsCount,
  };
}

/**
 * 构造登录/刷新响应。
 * @param user mock 用户
 * @returns AuthResponse
 */
function authResponse(user: MockUser) {
  return {
    accessToken: `mock-access-${user.id}`,
    tokenType: "Bearer",
    expiresIn: 900,
    user: toCurrentUser(user),
  };
}

/**
 * 管理端用户视图。
 * @param user mock 用户
 * @returns AdminUser
 */
function toAdminUser(user: MockUser): AdminUser {
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    role: user.role,
    enabled: user.enabled,
    deleted: user.deleted,
    createdAt: user.createdAt,
    lastLoginAt: user.lastLoginAt,
    disabledReason: user.disabledReason,
    // TODO(mock): 演示数据，真实接口落地后删除——用户行「答题数」列的演示值。
    attemptsCount: user.attemptsCount,
  };
}

/**
 * 句子视图裁掉 mock 内部的 bankId。
 * @param sentence mock 句子
 * @returns API SentenceView
 */
function toSentence(sentence: MockSentence) {
  const { bankId: _bankId, ...view } = sentence;
  return view;
}

/**
 * 管理端题目视图：保留 bankId，供题目页显示归属与批量移动。
 * @param sentence mock 句子
 * @returns 带 bankId 的题目视图
 */
function toAdminSentence(sentence: MockSentence) {
  return { ...toSentence(sentence), bankId: sentence.bankId };
}

/**
 * 复习卡裁掉 mock 内部的 userId，并 join 句子补中文（API 契约不含，复习行用它替代裸 ID）。
 * @param review mock 复习卡
 * @param db mock 数据库
 * @returns API Review
 */
function toReview(review: MockReview, db: MockDb): Review {
  const { userId: _userId, ...view } = review;
  // TODO(mock): 演示数据，真实接口落地后删除——mock 复习卡 join 句子补中文，避免复习行显示裸 ID。
  const zh = db.sentences.find((sentence) => sentence.id === review.sentenceId)?.zh ?? null;
  return { ...view, zh };
}

/**
 * 复核/端到端响应中使用的复习卡。
 * @param review mock 卡或 null
 * @param db mock 数据库
 * @returns Review 或 null
 */
function reviewOrNull(review: MockReview | null, db: MockDb): Review | null {
  return review ? toReview(review, db) : null;
}

/**
 * 读取 JSON 请求体；解析失败对应 40001。
 * @param init fetch 初始化
 * @returns 解析后的 body
 */
async function readBody(init: RequestInit): Promise<unknown> {
  const body = init.body;
  if (body === undefined || body === null) {
    return null;
  }
  if (typeof FormData !== "undefined" && body instanceof FormData) {
    return body;
  }
  if (typeof body === "string") {
    try {
      return JSON.parse(body) as unknown;
    } catch {
      fail(400, 40001, "请求体格式不正确");
    }
  }
  return body;
}

/**
 * 从 JSON body 中取对象。
 * @param body 已解析 body
 * @returns 对象或空对象
 */
function asObject(body: unknown): Record<string, unknown> {
  return typeof body === "object" && body !== null && !Array.isArray(body)
    ? (body as Record<string, unknown>)
    : {};
}

/**
 * 解析分页参数；越界或非数字按契约返回 40004。
 * @param params URL 查询参数
 * @returns page/size
 */
function pagination(params: URLSearchParams): { page: number; size: number } {
  const page = Number(params.get("page") ?? 1);
  const size = Number(params.get("size") ?? DEFAULT_PAGE_SIZE);
  if (!Number.isInteger(page) || page < 1 || !Number.isInteger(size) || size < 1 || size > 100) {
    fail(400, 40004, "分页参数不合法");
  }
  return { page, size };
}

/**
 * 按 YYYY-MM-DD 区间过滤 ISO 时间。
 * @param iso ISO 时间
 * @param from 起始本地日期
 * @param to 结束本地日期
 * @returns 是否落在区间内
 */
function inDateRange(iso: string, from: string | null, to: string | null): boolean {
  const day = iso.slice(0, 10);
  if (from && day < from) {
    return false;
  }
  if (to && day > to) {
    return false;
  }
  return true;
}

/**
 * 由对象类型与 id 推导人类可读名；审计行用它替代裸 UUID 展示。
 * TODO(mock): 演示数据，真实接口落地后删除——服务端审计应直接返回 targetLabel。
 * @param db mock 数据库
 * @param targetType 对象类型
 * @param targetId 对象 id
 * @returns 可读名；无法推导时为 null
 */
function targetLabelOf(
  db: MockDb,
  targetType: string | null,
  targetId: string | null,
): string | null {
  if (!targetId) {
    return null;
  }
  if (targetType === "USER") {
    return db.users.find((user) => user.id === targetId)?.username ?? null;
  }
  if (targetType === "BANK") {
    return db.banks.find((bank) => bank.id === targetId)?.name ?? null;
  }
  if (targetType === "SENTENCE") {
    return db.sentences.find((sentence) => sentence.id === targetId)?.zh ?? null;
  }
  if (targetType === "ATTEMPT") {
    const attempt = db.attempts.find((item) => item.request.attemptId === targetId);
    if (!attempt) {
      return null;
    }
    const who = db.users.find((user) => user.id === attempt.userId)?.username ?? "用户";
    return `${who} · ${attempt.zh}`;
  }
  if (targetType === "IMPORT_BATCH") {
    const batch = db.imports.find((item) => item.batchId === targetId);
    return batch ? (db.banks.find((bank) => bank.id === batch.bankId)?.name ?? null) : null;
  }
  return null;
}

/**
 * 追加一条操作审计；mock 的所有管理端写操作都留痕。
 * @param db mock 数据库
 * @param actor 操作者
 * @param action 稳定英文动作
 * @param targetType 对象类型
 * @param targetId 对象 id
 * @param reason 原因
 */
function appendAudit(
  db: MockDb,
  actor: MockUser,
  action: string,
  targetType: string | null,
  targetId: string | null,
  reason: string | null,
): void {
  const entry: AuditLog = {
    id: nextMockId(),
    module: "admin",
    actorId: actor.id,
    actorName: actor.username,
    actorRole: actor.role,
    clientIp: "127.0.0.1",
    action,
    targetType,
    targetId,
    // TODO(mock): 演示数据，真实接口落地后删除——审计行的人类可读对象名。
    targetLabel: targetLabelOf(db, targetType, targetId),
    reason,
    outcome: "SUCCESS",
    durationMs: 12,
    createdAt: new Date().toISOString(),
  };
  db.auditLogs.unshift(entry);
}

/**
 * 判断题库是否对当前用户可见。
 * @param bank 题库
 * @param user 当前用户或 null
 * @returns 是否可访问
 */
function canAccessBank(bank: BankView, user: MockUser | null): boolean {
  if (bank.status !== "PUBLISHED" && user?.role !== "ADMIN" && bank.ownerId !== user?.id) {
    return false;
  }
  if (bank.visibility === "PUBLIC") {
    return true;
  }
  if (!user) {
    return false;
  }
  return user.role === "ADMIN" || bank.ownerId === user.id;
}

/**
 * 计算复习卡四档评分；与 DATA-MODEL §8.2 的派生规则一致。
 * @param score 分数；REVEAL 为 null
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
 * 按句子查归属题库；句子可能挂在已下架的题库上，缺失时给一个不可访问的占位题库，
 * 让 canAccessBank 统一拒绝，与「下架题库的句子不再出题」一致。
 * @param db mock 数据库
 * @param sentence 句子
 * @returns 句子归属的题库或不可访问占位
 */
function bankOf(db: MockDb, sentence: MockSentence): BankView {
  return (
    db.banks.find((bank) => bank.id === sentence.bankId) ?? {
      id: sentence.bankId,
      ownerId: null,
      name: "",
      description: null,
      visibility: "PRIVATE",
      status: "DISABLED",
      version: 0,
      sentenceCount: 0,
    }
  );
}

/**
 * 更新或创建复习卡；RETRY 不排程。
 * 间隔规则与 lib/local-engine/engine.ts 的 scheduleReview 保持一致（#8 服务端切片落地后统一）：
 * - 答错 / 看答案 → LEARNING、明天到期、lapses+1；
 * - 学习中全对 → 间隔 min(7, 原间隔+2)，间隔 ≥5 归档 MASTERED；
 * - 已归档全对 → 保持 MASTERED，只刷新复习痕迹，不动下次到期时间；
 * - 新题全对 → 保持 NEW 不建卡（错才入队），已有的 NEW 卡同样不动。
 * @param db mock 数据库
 * @param user 用户
 * @param sentence 句子
 * @param mode 模式
 * @param rating 评分
 * @param score 分数
 * @param kind 提交类型
 * @returns 更新后的卡；RETRY 且没有卡、或新题全对时为 null
 */
function scheduleReview(
  db: MockDb,
  user: MockUser,
  sentence: MockSentence,
  mode: "CLOZE" | "FREE",
  rating: number,
  score: number | null,
  kind: "FIRST" | "RETRY" | "REVEAL",
): MockReview | null {
  let card = db.reviews.find(
    (item) => item.userId === user.id && item.sentenceId === sentence.id && item.mode === mode,
  );
  if (kind === "RETRY") {
    return card ?? null;
  }

  const now = new Date();
  const failed = kind === "REVEAL" || score === null || score < 100;
  if (failed) {
    // 答错 / 看答案：置 LEARNING、明天到期；已归档卡同样回炉（引擎的 failed 分支）。
    if (!card) {
      card = {
        id: nextMockId(),
        userId: user.id,
        sentenceId: sentence.id,
        mode,
        state: "NEW",
        fsrsState: "NEW",
        dueAt: now.toISOString(),
        stability: 0,
        difficulty: 0,
        reps: 0,
        lapses: 0,
        scheduledDays: 0,
        lastRating: null,
        lastReviewedAt: null,
      };
      db.reviews.push(card);
    }
    card.reps += 1;
    card.lapses = (card.lapses ?? 0) + 1;
    card.state = "LEARNING";
    card.fsrsState = "LEARNING";
    card.dueAt = new Date(now.getTime() + 86_400_000).toISOString();
    card.scheduledDays = 1;
    card.stability = 2.4;
    card.difficulty = 5.3;
    card.lastRating = rating;
    card.lastReviewedAt = now.toISOString();
    card.rating = rating;
    return card;
  }
  if (card && card.state === "LEARNING") {
    // 全对且在学习中：间隔 +2（封顶 7），间隔 ≥5 归档——「两全对 → 归档」的来源。
    const interval = Math.min(7, (card.scheduledDays || 0) + 2);
    const mastered = interval >= 5;
    card.reps += 1;
    card.scheduledDays = interval;
    card.dueAt = new Date(now.getTime() + interval * 86_400_000).toISOString();
    card.state = mastered ? "MASTERED" : "LEARNING";
    card.fsrsState = mastered ? "REVIEW" : "LEARNING";
    card.stability = mastered ? 40 : 3.2;
    card.difficulty = 5.1;
    card.lastRating = rating;
    card.lastReviewedAt = now.toISOString();
    card.rating = rating;
    return card;
  }
  if (card && card.state === "MASTERED") {
    // 已归档卡全对：保持归档，只刷新复习痕迹。
    card.reps += 1;
    card.lastRating = rating;
    card.lastReviewedAt = now.toISOString();
    card.rating = rating;
    return card;
  }
  // 新题全对保持 NEW（错才入队）；没有卡时不建卡。
  return card ?? null;
}

/**
 * 构造 REVEAL 的判分结果；不计分，摘要明确「学习中」。
 * @param sentence 句子
 * @returns GradingResult
 */
function revealResult(sentence: MockSentence): GradingResult {
  const total = sentence.blank.reduce((sum, chunkIndex) => {
    const chunk = sentence.chunks.find((item) => item.index === chunkIndex);
    return sum + (chunk?.answers[0]?.split(/\s+/).length ?? 0);
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
 * 执行本地判分；填空与 free 都走纯函数，REVEAL 不计分。
 * @param sentence 句子
 * @param body 已校验的 attempt 请求
 * @returns GradingResult
 */
function gradeAttempt(
  sentence: MockSentence,
  body: {
    kind: "FIRST" | "RETRY" | "REVEAL";
    answer: {
      slots?: Array<{ chunkIndex: number; value: string }>;
      text?: string;
      revealed?: boolean;
    };
  },
): GradingResult {
  if (body.kind === "REVEAL") {
    return revealResult(sentence);
  }
  if (body.answer.text !== undefined && sentence.blank.length === 0) {
    return gradeFree({ sentence: sentence.zh, ref: sentence.ref, text: body.answer.text });
  }
  const slotMap = new Map((body.answer.slots ?? []).map((slot) => [slot.chunkIndex, slot.value]));
  const answers = sentence.blank.map((chunkIndex) => slotMap.get(chunkIndex) ?? "");
  return gradeCloze({
    sentence: sentence.zh,
    chunks: sentence.chunks,
    blank: sentence.blank,
    answers,
  });
}

/**
 * 构造提交响应。
 * @param body attempt 请求
 * @param sentence 句子
 * @param result 判分结果
 * @param review 复习卡
 * @param replayed 是否幂等重放
 * @param gradingId 判分 id
 * @param db mock 数据库（复习卡投影 join 句子用）
 * @returns AttemptResponse
 */
function buildAttemptResponse(
  body: {
    attemptId: string;
    sentenceId: string;
    sentenceVersionId: string;
    mode: "CLOZE" | "FREE";
    kind: "FIRST" | "RETRY" | "REVEAL";
  },
  sentence: MockSentence,
  result: GradingResult,
  review: MockReview | null,
  replayed: boolean,
  gradingId: string,
  db: MockDb,
): AttemptResponse {
  const createdAt = new Date().toISOString();
  return {
    attemptId: body.attemptId,
    sentenceId: body.sentenceId,
    sentenceVersionId: body.sentenceVersionId,
    mode: body.mode,
    kind: body.kind,
    score: result.score,
    maxScore: 100,
    result,
    grading: {
      id: gradingId,
      sequence: 1,
      grader: "LOCAL",
      normalizerVersion: "norm-1",
      rubricVersion: "rubric-1",
      createdAt,
    },
    review: reviewOrNull(review, db),
    reference: { ref: sentence.ref, note: sentence.note ?? null },
    nextAction: {
      retryRequired: result.score !== null && result.score < 100 && body.kind === "FIRST",
      canReveal: body.kind !== "REVEAL",
    },
    replayed,
  };
}

/**
 * 生成题目视图。
 * @param sentence 句子
 * @returns API 题目
 */
function practiceSentence(sentence: MockSentence): PracticeSentence {
  return toSentence(sentence) as PracticeSentence;
}

/**
 * 计算当前用户的错题行：按句子取最近一次判分，保留批注明细与「不认同」标记。
 * 错题取题（/practice/next?errors=1）与错题列表（/practice/wrong-questions）共用同一口径：
 * 最近一次全对视为已修复；最近一次为看答案（score=null）的句子保留；
 * 批注全被「不认同」的整题在 includeDisputed=false 时隐藏。
 * 行顺序跟随 latest Map 的插入序（attempts 最新在前），调用方按需再排序。
 * @param db mock 数据库
 * @param user 当前用户
 * @param includeDisputed 是否包含批注全被排除的错题
 * @returns 错题行（API 形状，含 sentenceId/mode/dueAt）
 */
function wrongQuestionItems(db: MockDb, user: MockUser, includeDisputed: boolean) {
  const disputed = new Set(
    db.appeals
      .filter((appeal) => appeal.kind === "DISPUTE" && appeal.status === "ACCEPTED")
      .map((appeal) => `${appeal.attemptId}:${appeal.annotationKey}`),
  );
  const latest = new Map<string, MockAttempt>();
  for (const attempt of db.attempts.filter((item) => item.userId === user.id)) {
    const current = latest.get(attempt.request.sentenceId);
    if (!current || attempt.createdAt > current.createdAt) {
      latest.set(attempt.request.sentenceId, attempt);
    }
  }
  const items = [];
  for (const [sentenceId, attempt] of latest) {
    const sentence = db.sentences.find((item) => item.id === sentenceId);
    if (!sentence) {
      continue;
    }
    if (attempt.response.score !== null && attempt.response.score >= 100) {
      continue;
    }
    const incorrect = attempt.response.result.annotations.filter(
      (annotation) => !annotation.correct,
    );
    const visible = includeDisputed
      ? incorrect
      : incorrect.filter(
          (annotation) => !disputed.has(`${attempt.request.attemptId}:${annotation.key}`),
        );
    if (visible.length === 0 && incorrect.length > 0) {
      continue;
    }
    const card = db.reviews.find(
      (review) =>
        review.userId === user.id &&
        review.sentenceId === sentenceId &&
        review.mode === attempt.request.mode,
    );
    const wrongCount = db.attempts.filter(
      (item) =>
        item.userId === user.id &&
        item.request.sentenceId === sentenceId &&
        item.response.score !== null &&
        item.response.score < 100,
    ).length;
    items.push({
      sentenceId,
      mode: attempt.request.mode,
      state: card?.state ?? "NEW",
      dueAt: card?.dueAt ?? new Date().toISOString(),
      lastScore: attempt.response.score,
      lastAttemptId: attempt.request.attemptId,
      wrongCount,
      tags: sentence.tags,
      zh: sentence.zh,
      annotations: visible.map((annotation) => ({
        key: annotation.key,
        tag: annotation.tag,
        message: annotation.message,
        disputed: disputed.has(`${attempt.request.attemptId}:${annotation.key}`),
      })),
    });
  }
  return items;
}

/**
 * 导入批次视图；裁掉 mock 内部 rawRows 与 rows。
 * @param batch mock 批次
 * @returns ImportBatchView
 */
function toImportBatchView(batch: MockImportBatch): ImportBatchView {
  return {
    batchId: batch.batchId,
    bankId: batch.bankId,
    status: batch.status,
    format: batch.format,
    originalFilename: batch.originalFilename,
    totalRows: batch.totalRows,
    validRows: batch.validRows,
    invalidRows: batch.invalidRows,
    importedRows: batch.importedRows,
    createdAt: batch.createdAt,
    previewedAt: batch.previewedAt,
    committedAt: batch.committedAt,
    errorSummary: batch.errorSummary,
  };
}

/**
 * 复制上传批次视图；mock 内部对象可直接返回，这里保留浅拷贝以防调用方改写库内状态。
 * @param batch 上传批次
 * @returns 批次快照
 */
function toUploadBatchView(batch: MockUploadBatch): MockUploadBatch {
  return { ...batch };
}

/**
 * 模拟后台处理进度：上传后随时间推进 processedRows，超过 1.2 秒视为处理完成。
 * 失败批次停在原状态，由 resume 端点重新拉起。
 * @param batch 上传批次
 * @returns 推进后的批次快照
 */
function advanceUpload(batch: MockUploadBatch): MockUploadBatch {
  if (batch.status !== "PROCESSING" && batch.status !== "UPLOADED") {
    return toUploadBatchView(batch);
  }
  const elapsed = Date.now() - new Date(batch.createdAt).getTime();
  if (elapsed < 1200) {
    batch.processedRows = Math.min(batch.totalRows, Math.floor(batch.totalRows * (elapsed / 1200)));
    persistMockDb();
    return toUploadBatchView(batch);
  }
  batch.processedRows = batch.totalRows;
  batch.status = "COMPLETED";
  batch.successRows = Math.max(0, batch.totalRows - batch.failedRows);
  batch.failedRows = batch.totalRows - batch.successRows;
  batch.errorMessage = batch.failedRows > 0 ? `${batch.failedRows} 行未通过校验` : null;
  persistMockDb();
  return toUploadBatchView(batch);
}

/**
 * 校验导入行；返回逐行错误，空数组代表通过。
 * @param raw 原始行
 * @param rowNo 行号
 * @returns 错误数组
 */ function validateImportRow(raw: unknown, rowNo: number): ImportRowError[] {
  const errors: ImportRowError[] = [];
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    return [{ code: "ROW_NOT_OBJECT", field: null, message: `第 ${rowNo} 行不是对象` }];
  }
  const row = raw as Record<string, unknown>;
  const zh = typeof row.zh === "string" ? row.zh.trim() : "";
  const ref = typeof row.ref === "string" ? row.ref.trim() : "";
  const note = typeof row.note === "string" ? row.note.trim() : "";
  if (Array.from(zh).length < 4 || Array.from(zh).length > 60) {
    errors.push({ code: "ZH_LENGTH_INVALID", field: "zh", message: "中文长度必须为 4–60 字" });
  }
  if (ref.length < 8 || ref.length > 300) {
    errors.push({ code: "REF_LENGTH_INVALID", field: "ref", message: "参考译文长度必须为 8–300" });
  }
  if (Array.from(note).length < 20 || Array.from(note).length > 300) {
    errors.push({ code: "NOTE_TOO_SHORT", field: "note", message: "解析必须为 20–300 字" });
  }
  const chunksRaw = Array.isArray(row.chunks) ? row.chunks : [];
  if (chunksRaw.length === 0) {
    errors.push({ code: "CHUNKS_EMPTY", field: "chunks", message: "chunks 不能为空" });
  }
  const chunkIndexes = new Set<number>();
  const canonicalParts: string[] = [];
  chunksRaw.forEach((chunk, index) => {
    if (typeof chunk !== "object" || chunk === null) {
      errors.push({ code: "CHUNK_INVALID", field: `chunks[${index}]`, message: "语块必须是对象" });
      return;
    }
    const item = chunk as Record<string, unknown>;
    const chunkIndex = typeof item.index === "number" ? item.index : index;
    chunkIndexes.add(chunkIndex);
    const answers = Array.isArray(item.answers)
      ? item.answers.filter(
          (answer): answer is string => typeof answer === "string" && answer.trim() !== "",
        )
      : [];
    if (answers.length === 0) {
      errors.push({
        code: "CHUNK_ANSWER_EMPTY",
        field: `chunks[${index}].answers`,
        message: "每个语块至少一个非空答案",
      });
    } else {
      canonicalParts.push(answers[0]!);
    }
    if (typeof item.zh !== "string" || item.zh.trim() === "") {
      errors.push({
        code: "CHUNK_ZH_EMPTY",
        field: `chunks[${index}].zh`,
        message: "语块中文不能为空",
      });
    }
  });
  const blank = Array.isArray(row.blank) ? row.blank : [];
  if (blank.length === 0) {
    errors.push({ code: "BLANK_EMPTY", field: "blank", message: "blank 至少一个下标" });
  }
  for (const value of blank) {
    if (typeof value !== "number" || !chunkIndexes.has(value)) {
      errors.push({
        code: "BLANK_INDEX_INVALID",
        field: "blank",
        message: `blank 下标 ${String(value)} 不存在于 chunks`,
      });
    }
  }
  if (
    canonicalParts.length > 0 &&
    normalizeAnswer(canonicalParts.join(" ")) !== normalizeAnswer(ref)
  ) {
    errors.push({
      code: "CHUNK_REF_MISMATCH",
      field: "chunks",
      message: "chunks[].answers[0] 按序拼接与 ref 不一致",
    });
  }
  const tags = Array.isArray(row.tags) ? row.tags.filter((tag) => typeof tag === "string") : [];
  if (tags.length < 1 || tags.length > 5) {
    errors.push({ code: "TAGS_INVALID", field: "tags", message: "tags 必须为 1–5 个字符串" });
  }
  return errors;
}

/**
 * 把合法导入行转换成句子；只在 commit 时调用。
 * @param raw 原始行
 * @param bankId 题库 id
 * @param version 银行修订号
 * @returns 新的 mock 句子
 */
function sentenceFromImport(
  raw: Record<string, unknown>,
  bankId: string,
  version: number,
): MockSentence {
  const chunksRaw = Array.isArray(raw.chunks) ? raw.chunks : [];
  const parsedChunks = chunksRaw.map((chunk, index) => {
    const item = chunk as Record<string, unknown>;
    return {
      index: typeof item.index === "number" ? item.index : index,
      zh: String(item.zh ?? ""),
      answers: (Array.isArray(item.answers) ? item.answers : []).filter(
        (answer): answer is string => typeof answer === "string",
      ),
      tag: typeof item.tag === "string" ? item.tag : null,
      note: typeof item.note === "string" ? item.note : null,
    };
  });
  return {
    id: mockUlid(200 + (Number.parseInt(nextMockId(), 10) % 500)),
    bankId,
    version,
    sentenceVersionId: nextMockId(),
    questionVersion: 1,
    status: "PUBLISHED",
    deleted: false,
    zh: String(raw.zh ?? ""),
    ref: String(raw.ref ?? ""),
    note: String(raw.note ?? ""),
    chunks: parsedChunks,
    blank: (Array.isArray(raw.blank) ? raw.blank : []).filter(
      (value): value is number => typeof value === "number",
    ),
    tags: (Array.isArray(raw.tags) ? raw.tags : []).filter(
      (tag): tag is string => typeof tag === "string",
    ),
    level: typeof raw.level === "string" ? raw.level : null,
  };
}

/**
 * 解析 CSV；只支持简单逗号分隔与表头，足够演示 preview/commit。
 * @param text CSV 文本
 * @returns 行对象数组
 */
function parseCsvRows(text: string): Array<Record<string, unknown>> {
  const lines = text
    .replace(/\r\n/g, "\n")
    .split("\n")
    .filter((line) => line.trim() !== "");
  if (lines.length < 2) {
    fail(400, 40001, "CSV 至少需要表头与一行数据");
  }
  const headers = lines[0]!.split(",").map((header) => header.trim());
  return lines.slice(1).map((line) => {
    const cells = line.split(",");
    const row: Record<string, unknown> = {};
    headers.forEach((header, index) => {
      const value = (cells[index] ?? "").trim();
      if (header === "chunks" || header === "blank" || header === "tags") {
        try {
          row[header] = JSON.parse(value) as unknown;
        } catch {
          row[header] = value;
        }
      } else {
        row[header] = value;
      }
    });
    return row;
  });
}

/**
 * 处理所有 mock API 请求；路径与 API.md 的 planned/implemented 端点一一对应。
 * @param path `/api` 之后的路径（含查询串）
 * @param init fetch 初始化
 * @returns 解包后的 data
 */
export async function handleMockRequest(path: string, init: RequestInit = {}): Promise<unknown> {
  await delay();
  const method = (init.method ?? "GET").toUpperCase();
  const url = new URL(path, "http://mock.yilian.local");
  const pathname = url.pathname;
  const params = url.searchParams;
  const db = getMockDb();
  const body = await readBody(init);

  if (pathname === "/auth/csrf" && method === "GET") {
    return { token: "mock-csrf-token", headerName: "X-XSRF-TOKEN", parameterName: "_csrf" };
  }

  if (pathname === "/auth/login" && method === "POST") {
    const input = asObject(body);
    const username = String(input.username ?? "");
    const password = String(input.password ?? "");
    const user = db.users.find(
      (item) =>
        (item.username === username || item.email === username) &&
        item.password === password &&
        item.enabled &&
        !item.deleted,
    );
    if (!user) {
      fail(401, 40101, "用户名或密码错误");
    }
    db.sessionUserId = user.id;
    user.lastLoginAt = new Date().toISOString();
    persistMockDb();
    return authResponse(user);
  }

  if (pathname === "/auth/refresh" && method === "POST") {
    const user = currentUser(db);
    if (!user) {
      fail(401, 40100, "请先登录");
    }
    return authResponse(user);
  }

  if (pathname === "/auth/logout" && method === "POST") {
    db.sessionUserId = null;
    persistMockDb();
    return null;
  }

  if (pathname === "/auth/me" && method === "GET") {
    return toCurrentUser(requireUser(db));
  }

  if (pathname === "/auth/register" && method === "POST") {
    const parsed = registerRequestSchema.safeParse(body);
    if (!parsed.success) {
      fail(400, 40000, parsed.error.issues[0]?.message ?? "请求参数不合法");
    }
    const input = parsed.data;
    // 邀请码与用户页邀请码区共用一份数据：未使用才放行，注册后标记已用（一码一人）。
    const invite = db.invites.find((item) => item.code === input.inviteCode);
    if (!invite || invite.used || db.inviteUsedBy) {
      fail(400, 40002, "邀请码无效或已过期");
    }
    if (db.users.some((user) => user.email === input.email)) {
      fail(409, 40901, "邮箱已被使用");
    }
    const username =
      input.username ?? input.email.split("@")[0]?.replace(/[^A-Za-z0-9._-]/g, "") ?? "learner";
    if (db.users.some((user) => user.username === username)) {
      fail(409, 40902, "用户名已被使用");
    }
    if (new TextEncoder().encode(input.password).length > 72) {
      fail(400, 40003, "密码强度不足");
    }
    if (
      input.password.toLowerCase() === username.toLowerCase() ||
      input.password.toLowerCase() === input.email.toLowerCase()
    ) {
      fail(400, 40003, "密码强度不足");
    }
    const user: MockUser = {
      id: nextMockId(),
      username,
      email: input.email,
      role: "USER",
      enabled: true,
      deleted: false,
      createdAt: new Date().toISOString(),
      lastLoginAt: new Date().toISOString(),
      disabledReason: null,
      password: input.password,
      settings: {
        timezone: "Asia/Shanghai",
        dailyGoal: 3,
        ratingStandard: "TEM8",
        desiredRetention: 0.9,
        theme: "paper",
        notificationsEnabled: false,
      },
    };
    db.users.push(user);
    db.inviteUsedBy = user.id;
    invite.used = true;
    invite.usedBy = user.id;
    db.sessionUserId = user.id;
    persistMockDb();
    return authResponse(user);
  }

  if (pathname === "/me/settings" && method === "GET") {
    const user = requireUser(db);
    return user.settings;
  }

  if (pathname === "/me/settings" && method === "PUT") {
    const user = requireUser(db);
    const input = asObject(body);
    const next = { ...user.settings };
    if (input.timezone !== undefined) {
      const timezone = String(input.timezone);
      try {
        new Intl.DateTimeFormat("en-US", { timeZone: timezone }).format(new Date());
      } catch {
        fail(400, 40000, "时区不合法");
      }
      next.timezone = timezone;
    }
    if (input.dailyGoal !== undefined) {
      const goal = Number(input.dailyGoal);
      if (![1, 3, 5].includes(goal)) {
        fail(400, 40000, "每日目标只能是 1、3 或 5");
      }
      next.dailyGoal = goal as 1 | 3 | 5;
    }
    if (input.ratingStandard !== undefined) {
      if (!["TEM8", "CATTI", "IELTS"].includes(String(input.ratingStandard))) {
        fail(400, 40000, "评分标准不合法");
      }
      next.ratingStandard = String(input.ratingStandard) as "TEM8" | "CATTI" | "IELTS";
    }
    if (input.desiredRetention !== undefined) {
      const retention = Number(input.desiredRetention);
      if (!Number.isFinite(retention) || retention < 0.8 || retention > 0.97) {
        fail(400, 40000, "期望保留率必须在 0.80–0.97 之间");
      }
      next.desiredRetention = retention;
    }
    if (input.theme !== undefined) {
      if (!["paper", "night"].includes(String(input.theme))) {
        fail(400, 40000, "主题不合法");
      }
      next.theme = String(input.theme) as "paper" | "night";
    }
    if (input.notificationsEnabled !== undefined) {
      next.notificationsEnabled = Boolean(input.notificationsEnabled);
    }
    user.settings = next;
    persistMockDb();
    return next;
  }

  if (pathname === "/banks" && method === "GET") {
    const user = currentUser(db);
    const keyword = (params.get("keyword") ?? "").trim().toLowerCase();
    const visibility = params.get("visibility");
    const status = params.get("status") ?? "PUBLISHED";
    const filtered = db.banks.filter((bank) => {
      if (!canAccessBank(bank, user)) {
        return false;
      }
      if (status && bank.status !== status && bank.ownerId !== user?.id && user?.role !== "ADMIN") {
        return false;
      }
      if (visibility && bank.visibility !== visibility) {
        return false;
      }
      if (keyword && !`${bank.name} ${bank.description ?? ""}`.toLowerCase().includes(keyword)) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    return paginate(filtered, page, size);
  }

  const bankDetailMatch = pathname.match(/^\/banks\/([^/]+)$/);
  if (bankDetailMatch && method === "GET") {
    const user = currentUser(db);
    const bank = db.banks.find((item) => item.id === decodeURIComponent(bankDetailMatch[1]!));
    if (!bank) {
      fail(404, 40401, "题库不存在");
    }
    if (!canAccessBank(bank, user)) {
      fail(403, 40300, "没有访问权限");
    }
    return bank;
  }

  const bankSentencesMatch = pathname.match(/^\/banks\/([^/]+)\/sentences$/);
  if (bankSentencesMatch && method === "GET") {
    const user = currentUser(db);
    const bank = db.banks.find((item) => item.id === decodeURIComponent(bankSentencesMatch[1]!));
    if (!bank) {
      fail(404, 40401, "题库不存在");
    }
    if (!canAccessBank(bank, user)) {
      fail(401, 40100, "请先登录");
    }
    const sinceVersion = Number(params.get("sinceVersion") ?? 0);
    const limit = Number(params.get("limit") ?? 100);
    if (!Number.isInteger(sinceVersion) || sinceVersion < 0 || sinceVersion > bank.version) {
      fail(400, 40005, "版本号不合法");
    }
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) {
      fail(400, 40004, "分页参数不合法");
    }
    const all = db.sentences
      .filter((sentence) => sentence.bankId === bank.id && sentence.version > sinceVersion)
      .sort((a, b) => a.version - b.version);
    const items = all
      .slice(0, limit)
      .map((sentence) =>
        sentence.deleted
          ? { id: sentence.id, version: sentence.version, deleted: true }
          : toSentence(sentence),
      );
    const maxVersion = all
      .slice(0, limit)
      .reduce((max, item) => Math.max(max, item.version), sinceVersion);
    return {
      version: bank.version,
      items,
      nextSinceVersion: maxVersion,
      hasMore: all.length > limit,
    };
  }

  if (pathname === "/practice/next" && method === "GET") {
    const user = requireUser(db);
    const mode = (params.get("mode") ?? "CLOZE") as "CLOZE" | "FREE";
    if (mode !== "CLOZE" && mode !== "FREE") {
      fail(400, 40000, "练习模式不合法");
    }
    const bankId = params.get("bankId");
    const exclude = params.get("excludeSentenceId");
    // excludeIds 为逗号分隔的排除列表（下一步训练「换个题再练」使用）；旧的单题排除参数并入其中。
    const excludeList = new Set([
      ...(params.get("excludeIds") ?? "")
        .split(",")
        .map((value) => value.trim())
        .filter(Boolean),
      ...(exclude ? [exclude] : []),
    ]);
    const tagFilter = params.get("tag");
    const now = Date.now();
    const accessible = db.sentences.filter(
      (sentence) =>
        !sentence.deleted &&
        sentence.status === "PUBLISHED" &&
        (!bankId || sentence.bankId === bankId) &&
        (!tagFilter || sentence.tags.includes(tagFilter)) &&
        canAccessBank(bankOf(db, sentence), user) &&
        !excludeList.has(sentence.id),
    );
    // 复习页「重练」会带 sentenceId 指定这一句；指定句优先级最高，忽略 bankId/tag/exclude，
    // 找不到（已删除/无权限）时退回常规取题，避免死链把练习页打成空白。
    const specifiedId = params.get("sentenceId");
    if (specifiedId) {
      const specified = db.sentences.find(
        (sentence) =>
          sentence.id === specifiedId &&
          !sentence.deleted &&
          sentence.status === "PUBLISHED" &&
          canAccessBank(bankOf(db, sentence), user),
      );
      if (specified) {
        const card = db.reviews.find(
          (review) =>
            review.userId === user.id && review.sentenceId === specified.id && review.mode === mode,
        );
        return {
          sentence: practiceSentence(specified),
          review: reviewOrNull(card ?? null, db),
          selection: card ? "MANUAL" : "NEW",
        };
      }
    }
    // 今日页「错题」chip（errors=1）：只在错题里取下一题，口径与错题列表一致
    // （最近一次判分 <100 或看答案；批注全被「不认同」的整题默认跳过）。
    // bankId/tag 是显式筛选需要尊重；mode 不过滤——错题是句子级召回，练习模式由页面当前选择决定。
    // 排序：dueAt 升序取最紧急；同 dueAt 保持「最近做错在前」（错题列表的倒序口径）。
    if (params.get("errors") === "1") {
      const errorRows = wrongQuestionItems(db, user, false)
        .map((item) => ({ item, sentence: db.sentences.find((s) => s.id === item.sentenceId) }))
        .filter(({ item, sentence }) => {
          if (!sentence || sentence.deleted || sentence.status !== "PUBLISHED") {
            return false;
          }
          if (excludeList.has(item.sentenceId)) {
            return false;
          }
          if (bankId && sentence.bankId !== bankId) {
            return false;
          }
          if (tagFilter && !sentence.tags.includes(tagFilter)) {
            return false;
          }
          return canAccessBank(bankOf(db, sentence), user);
        })
        .sort((a, b) => a.item.dueAt.localeCompare(b.item.dueAt));
      const pickedError = errorRows[0];
      if (!pickedError?.sentence) {
        return null;
      }
      const card = db.reviews.find(
        (review) =>
          review.userId === user.id &&
          review.sentenceId === pickedError.item.sentenceId &&
          review.mode === pickedError.item.mode,
      );
      return {
        sentence: practiceSentence(pickedError.sentence),
        review: reviewOrNull(card ?? null, db),
        // 不改 nextPracticeSchema：错题召回是到期召回的变体（错题就在学习队列里），沿用 DUE。
        selection: "DUE",
      };
    }
    const dueCard = db.reviews
      .filter(
        (review) =>
          review.userId === user.id &&
          review.mode === mode &&
          review.state !== "NEW" &&
          new Date(review.dueAt).getTime() <= now,
      )
      .sort((a, b) => a.dueAt.localeCompare(b.dueAt))[0];
    if (dueCard) {
      const sentence = accessible.find((item) => item.id === dueCard.sentenceId);
      if (sentence) {
        return {
          sentence: practiceSentence(sentence),
          review: toReview(dueCard, db),
          selection: "DUE",
        };
      }
    }
    const reviewedIds = new Set(
      db.reviews
        .filter((review) => review.userId === user.id && review.mode === mode)
        .filter((review) => review.state !== "NEW")
        .map((review) => review.sentenceId),
    );
    const nextSentence = accessible
      .filter((sentence) => !reviewedIds.has(sentence.id))
      .sort((a, b) => a.id.localeCompare(b.id))[0];
    if (!nextSentence) {
      return null;
    }
    const card =
      db.reviews.find(
        (review) =>
          review.userId === user.id &&
          review.sentenceId === nextSentence.id &&
          review.mode === mode,
      ) ?? null;
    return {
      sentence: practiceSentence(nextSentence),
      review: reviewOrNull(card, db),
      selection: card?.state === "NEW" || !card ? "NEW" : "MANUAL",
    };
  }

  const appealMatch = pathname.match(
    /^\/practice\/attempts\/([^/]+)\/annotations\/([^/]+)\/(appeal|dispute)$/,
  );
  if (appealMatch && method === "POST") {
    const user = requireUser(db);
    const attemptId = decodeURIComponent(appealMatch[1]!);
    const annotationKey = decodeURIComponent(appealMatch[2]!);
    const kind: "APPEAL" | "DISPUTE" = appealMatch[3] === "appeal" ? "APPEAL" : "DISPUTE";
    const attempt = db.attempts.find(
      (item) => item.request.attemptId === attemptId && item.userId === user.id,
    );
    if (!attempt) {
      fail(404, 40403, "答题记录不存在");
    }
    const annotation = attempt.response.result.annotations.find(
      (item) => item.key === annotationKey,
    );
    if (!annotation) {
      fail(400, 40000, "批注不存在");
    }
    const existing = db.appeals.find(
      (item) =>
        item.attemptId === attemptId && item.annotationKey === annotationKey && item.kind === kind,
    );
    if (existing) {
      fail(409, 40900, "请求正在处理或已处理，请勿重复提交");
    }
    const input = asObject(body);
    const appeal = {
      id: nextMockId(),
      attemptId,
      annotationKey,
      kind,
      status: kind === "DISPUTE" ? ("ACCEPTED" as const) : ("PENDING" as const),
      proposedAnswer: typeof input.proposedAnswer === "string" ? input.proposedAnswer : null,
      reason: typeof input.reason === "string" ? input.reason : null,
    };
    db.appeals.push(appeal);
    persistMockDb();
    return { appealId: appeal.id, status: appeal.status, kind, annotationKey };
  }

  if (pathname === "/practice/attempts" && method === "POST") {
    const user = requireUser(db);
    const parsed = attemptRequestSchema.safeParse(body);
    if (!parsed.success) {
      fail(400, 40000, parsed.error.issues[0]?.message ?? "请求参数不合法");
    }
    const input = parsed.data;
    const sentence = db.sentences.find((item) => item.id === input.sentenceId);
    if (!sentence || sentence.deleted) {
      fail(404, 40402, "题目不存在");
    }
    if (String(sentence.sentenceVersionId) !== input.sentenceVersionId) {
      fail(400, 40000, "题目版本不匹配，请刷新后重试");
    }
    if (input.kind === "REVEAL" && input.answer.revealed !== true) {
      fail(400, 40000, "看答案必须标记 revealed");
    }
    if (input.kind === "RETRY") {
      const original = db.attempts.find(
        (item) =>
          item.request.attemptId === input.retryOfId &&
          item.userId === user.id &&
          item.request.sentenceId === input.sentenceId &&
          item.request.mode === input.mode,
      );
      if (!original) {
        fail(400, 40000, "改错记录必须指向同一题、同一模式的原始提交");
      }
    }
    const existing = db.attempts.find((item) => item.request.attemptId === input.attemptId);
    if (existing) {
      const sameAnswer =
        JSON.stringify(existing.request.answer) === JSON.stringify(input.answer) &&
        existing.request.sentenceId === input.sentenceId &&
        existing.request.mode === input.mode;
      if (existing.userId !== user.id || !sameAnswer) {
        fail(409, 40903, "该答题 id 已被其他内容使用");
      }
      return { ...existing.response, replayed: true };
    }

    const result = gradeAttempt(sentence, input);
    const rating = deriveRating(result.score, input.hintsUsed, input.durationMs, input.kind);
    const review = scheduleReview(db, user, sentence, input.mode, rating, result.score, input.kind);
    const response = buildAttemptResponse(input, sentence, result, review, false, nextMockId(), db);
    const attempt: MockAttempt = {
      userId: user.id,
      request: input,
      response,
      createdAt: new Date().toISOString(),
      zh: sentence.zh,
    };
    db.attempts.unshift(attempt);
    persistMockDb();
    return response;
  }

  const attemptDetailMatch = pathname.match(/^\/practice\/attempts\/([^/]+)$/);
  if (attemptDetailMatch && method === "GET") {
    const user = requireUser(db);
    const attempt = db.attempts.find(
      (item) =>
        item.request.attemptId === decodeURIComponent(attemptDetailMatch[1]!) &&
        item.userId === user.id,
    );
    if (!attempt) {
      fail(404, 40403, "答题记录不存在");
    }
    return attempt.response;
  }

  if (pathname === "/practice/wrong-questions" && method === "GET") {
    const user = requireUser(db);
    const includeDisputed = params.get("includeDisputed") === "true";
    const tag = params.get("tag");
    const bankId = params.get("bankId");
    const items = wrongQuestionItems(db, user, includeDisputed).filter((item) => {
      if (bankId) {
        const sentence = db.sentences.find((entry) => entry.id === item.sentenceId);
        if (!sentence || sentence.bankId !== bankId) {
          return false;
        }
      }
      if (tag && !item.tags.includes(tag)) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    return paginate(items, page, size);
  }

  if (pathname === "/practice/reviews" && method === "GET") {
    const user = requireUser(db);
    const state = params.get("state");
    const mode = params.get("mode");
    const dueOnly = params.get("due") === "true";
    const now = Date.now();
    const filtered = db.reviews
      .filter((review) => review.userId === user.id)
      .filter((review) => !state || review.state === state)
      .filter((review) => !mode || review.mode === mode)
      .filter((review) => !dueOnly || new Date(review.dueAt).getTime() <= now)
      .sort((a, b) => a.dueAt.localeCompare(b.dueAt));
    const { page, size } = pagination(params);
    const result = paginate(filtered, page, size);
    return { ...result, items: result.items.map((item) => toReview(item, db)) };
  }

  const resetReviewMatch = pathname.match(/^\/practice\/reviews\/([^/]+)\/reset$/);
  if (resetReviewMatch && method === "POST") {
    const user = requireUser(db);
    const card = db.reviews.find(
      (review) =>
        review.id === decodeURIComponent(resetReviewMatch[1]!) && review.userId === user.id,
    );
    if (!card) {
      fail(404, 40404, "复习记录不存在");
    }
    card.state = "NEW";
    card.fsrsState = "NEW";
    card.dueAt = new Date().toISOString();
    card.stability = 0;
    card.difficulty = 0;
    card.reps = 0;
    card.lapses = 0;
    card.scheduledDays = 0;
    card.lastRating = null;
    persistMockDb();
    return toReview(card, db);
  }

  if (pathname === "/practice/today" && method === "GET") {
    const user = requireUser(db);
    const timezone = user.settings.timezone;
    const today = localDateString(new Date(), timezone);
    const attempts = db.attempts.filter(
      (attempt) =>
        attempt.userId === user.id &&
        localDateString(new Date(attempt.createdAt), timezone) === today,
    );
    const scored = attempts.filter((attempt) => attempt.response.score !== null);
    const correct = scored.filter((attempt) => attempt.response.score === 100).length;
    const dueCount = db.reviews.filter(
      (review) =>
        review.userId === user.id &&
        review.state !== "MASTERED" &&
        new Date(review.dueAt).getTime() <= Date.now(),
    ).length;
    const days = new Set(
      db.attempts
        .filter((attempt) => attempt.userId === user.id)
        .map((attempt) => localDateString(new Date(attempt.createdAt), timezone)),
    );
    let streak = 0;
    const cursor = new Date();
    for (let index = 0; index < 366; index += 1) {
      const key = localDateString(cursor, timezone);
      if (days.has(key)) {
        streak += 1;
      } else if (
        index > 0 ||
        !days.has(localDateString(new Date(Date.now() - 86_400_000), timezone))
      ) {
        break;
      }
      cursor.setTime(cursor.getTime() - 86_400_000);
    }
    return {
      date: today,
      timezone,
      dueCount,
      doneCount: attempts.length,
      goal: user.settings.dailyGoal,
      correctRate: scored.length === 0 ? 0 : Math.round((correct / scored.length) * 1000) / 10,
      streakDays: streak,
    };
  }

  if (pathname === "/analytics/accuracy" && method === "GET") {
    const user = requireUser(db);
    const to = params.get("to") ?? localDateString(new Date(), user.settings.timezone);
    const from =
      params.get("from") ??
      localDateString(new Date(Date.now() - 29 * 86_400_000), user.settings.timezone);
    const attempts = db.attempts.filter(
      (attempt) => attempt.userId === user.id && inDateRange(attempt.createdAt, from, to),
    );
    const scored = attempts.filter((attempt) => attempt.response.score !== null);
    const correct = scored.filter((attempt) => attempt.response.score === 100).length;
    const average =
      scored.length === 0
        ? 0
        : scored.reduce((sum, attempt) => sum + (attempt.response.score ?? 0), 0) / scored.length;
    const byMode = (["CLOZE", "FREE"] as const).map((mode) => {
      const modeScored = scored.filter((attempt) => attempt.request.mode === mode);
      const modeCorrect = modeScored.filter((attempt) => attempt.response.score === 100).length;
      return {
        mode,
        attempts: modeScored.length,
        correctRate:
          modeScored.length === 0 ? 0 : Math.round((modeCorrect / modeScored.length) * 1000) / 10,
      };
    });
    const result: Accuracy = {
      from,
      to,
      totalAttempts: attempts.length,
      correctRate: scored.length === 0 ? 0 : Math.round((correct / scored.length) * 1000) / 10,
      averageScore: Math.round(average * 10) / 10,
      byMode,
    };
    return result;
  }

  if (pathname === "/analytics/error-tags" && method === "GET") {
    const user = requireUser(db);
    const to = params.get("to") ?? localDateString(new Date(), user.settings.timezone);
    const from =
      params.get("from") ??
      localDateString(new Date(Date.now() - 29 * 86_400_000), user.settings.timezone);
    const disputed = new Set(
      db.appeals
        .filter((appeal) => appeal.kind === "DISPUTE" && appeal.status === "ACCEPTED")
        .map((appeal) => `${appeal.attemptId}:${appeal.annotationKey}`),
    );
    const counts = new Map<string, number>();
    let total = 0;
    for (const attempt of db.attempts.filter(
      (item) => item.userId === user.id && inDateRange(item.createdAt, from, to),
    )) {
      for (const annotation of attempt.response.result.annotations) {
        if (annotation.correct) {
          continue;
        }
        if (disputed.has(`${attempt.request.attemptId}:${annotation.key}`)) {
          continue;
        }
        counts.set(annotation.tag, (counts.get(annotation.tag) ?? 0) + 1);
        total += 1;
      }
    }
    const items = [...counts.entries()]
      .map(([tag, count]) => ({
        tag,
        count,
        ratio: total === 0 ? 0 : Math.round((count / total) * 1000) / 10,
      }))
      .sort((a, b) => b.count - a.count);
    const result: ErrorTags = { from, to, items };
    return result;
  }

  if (pathname === "/admin/users" && method === "GET") {
    requireAdmin(db);
    const keyword = (params.get("keyword") ?? "").trim().toLowerCase();
    const role = params.get("role");
    const status = params.get("status");
    const filtered = db.users.filter((user) => {
      if (role && user.role !== role) {
        return false;
      }
      if (status === "active" && (!user.enabled || user.deleted)) {
        return false;
      }
      if (status === "disabled" && (user.enabled || user.deleted)) {
        return false;
      }
      if (status === "deleted" && !user.deleted) {
        return false;
      }
      if (keyword && !`${user.username} ${user.email ?? ""}`.toLowerCase().includes(keyword)) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    const result = paginate(filtered, page, size);
    return { ...result, items: result.items.map(toAdminUser) };
  }

  const userStatusMatch = pathname.match(/^\/admin\/users\/([^/]+)\/status$/);
  if (userStatusMatch && method === "PATCH") {
    const admin = requireAdmin(db);
    const input = asObject(body);
    const target = db.users.find((user) => user.id === decodeURIComponent(userStatusMatch[1]!));
    if (!target) {
      fail(404, 40400, "请求的资源不存在");
    }
    if (target.id === admin.id) {
      fail(409, 40900, "不能停用自己的账号");
    }
    const enabled = Boolean(input.enabled);
    const reason = String(input.reason ?? "").trim();
    if (!enabled && reason === "") {
      fail(400, 40000, "停用账号必须填写原因");
    }
    if (!enabled && target.role === "ADMIN") {
      const activeAdmins = db.users.filter(
        (user) => user.role === "ADMIN" && user.enabled && !user.deleted,
      );
      if (activeAdmins.length <= 1) {
        fail(409, 40900, "不能停用最后一个管理员");
      }
    }
    target.enabled = enabled;
    target.disabledReason = enabled ? null : reason;
    appendAudit(
      db,
      admin,
      enabled ? "USER_ENABLE" : "USER_DISABLE",
      "USER",
      target.id,
      reason || null,
    );
    persistMockDb();
    return toAdminUser(target);
  }

  const userRoleMatch = pathname.match(/^\/admin\/users\/([^/]+)\/role$/);
  if (userRoleMatch && method === "PATCH") {
    const admin = requireAdmin(db);
    const input = asObject(body);
    const target = db.users.find((user) => user.id === decodeURIComponent(userRoleMatch[1]!));
    if (!target) {
      fail(404, 40400, "请求的资源不存在");
    }
    const role = String(input.role);
    if (role !== "ADMIN" && role !== "USER") {
      fail(400, 40000, "角色不合法");
    }
    if (target.id === admin.id && role !== "ADMIN") {
      fail(409, 40900, "不能降级自己的管理员角色");
    }
    if (target.role === "ADMIN" && role === "USER") {
      const activeAdmins = db.users.filter(
        (user) => user.role === "ADMIN" && user.enabled && !user.deleted,
      );
      if (activeAdmins.length <= 1) {
        fail(409, 40900, "不能移除最后一个管理员");
      }
    }
    target.role = role;
    appendAudit(db, admin, "ROLE_CHANGE", "USER", target.id, String(input.reason ?? "") || null);
    persistMockDb();
    return toAdminUser(target);
  }

  if (pathname === "/admin/audit-logs" && method === "GET") {
    requireAdmin(db);
    const keyword = (params.get("keyword") ?? "").trim().toLowerCase();
    const module = params.get("module");
    const action = params.get("action");
    const actorId = params.get("actorId");
    const from = params.get("from");
    const to = params.get("to");
    const filtered = db.auditLogs.filter((log) => {
      if (module && log.module !== module) {
        return false;
      }
      if (action && log.action !== action) {
        return false;
      }
      if (actorId && log.actorId !== actorId) {
        return false;
      }
      if (!inDateRange(log.createdAt, from, to)) {
        return false;
      }
      if (
        keyword &&
        !`${log.action} ${log.actorName} ${log.targetType ?? ""} ${log.targetId ?? ""} ${log.reason ?? ""}`
          .toLowerCase()
          .includes(keyword)
      ) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    return paginate(filtered, page, size);
  }

  if (pathname === "/admin/error-logs" && method === "GET") {
    requireAdmin(db);
    const keyword = (params.get("keyword") ?? "").trim().toLowerCase();
    const module = params.get("module");
    const from = params.get("from");
    const to = params.get("to");
    const filtered = db.errorLogs.filter((log) => {
      if (module && log.module !== module) {
        return false;
      }
      if (!inDateRange(log.createdAt, from, to)) {
        return false;
      }
      if (
        keyword &&
        !`${log.exceptionClass} ${log.message ?? ""} ${log.traceId} ${log.operator ?? ""}`
          .toLowerCase()
          .includes(keyword)
      ) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    const result = paginate(filtered, page, size);
    return {
      ...result,
      items: result.items.map(({ stackTrace: _stackTrace, ...item }) => item),
    };
  }

  const errorDetailMatch = pathname.match(/^\/admin\/error-logs\/([^/]+)$/);
  if (errorDetailMatch && method === "GET") {
    requireAdmin(db);
    const log = db.errorLogs.find((item) => item.id === decodeURIComponent(errorDetailMatch[1]!));
    if (!log) {
      fail(404, 40400, "请求的资源不存在");
    }
    return log;
  }

  if (pathname === "/admin/banks" && method === "GET") {
    requireAdmin(db);
    const keyword = (params.get("keyword") ?? "").trim().toLowerCase();
    const status = params.get("status");
    const visibility = params.get("visibility");
    const filtered = db.banks.filter((bank) => {
      if (status && bank.status !== status) {
        return false;
      }
      if (visibility && bank.visibility !== visibility) {
        return false;
      }
      if (keyword && !`${bank.name} ${bank.description ?? ""}`.toLowerCase().includes(keyword)) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    return paginate(filtered, page, size);
  }

  if (pathname === "/admin/banks" && method === "POST") {
    const admin = requireAdmin(db);
    const parsed = bankCreateRequestSchema.safeParse(body);
    if (!parsed.success) {
      fail(400, 40000, parsed.error.issues[0]?.message ?? "请求参数不合法");
    }
    const bank: BankView = {
      id: nextMockId(),
      ownerId: null,
      name: parsed.data.name,
      description: parsed.data.description,
      visibility: parsed.data.visibility,
      status: "DRAFT",
      version: 0,
      sentenceCount: 0,
    };
    db.banks.unshift(bank);
    appendAudit(db, admin, "BANK_CREATE", "BANK", bank.id, null);
    persistMockDb();
    return bank;
  }

  const adminBankDetailMatch = pathname.match(/^\/admin\/banks\/([^/]+)$/);
  if (adminBankDetailMatch) {
    requireAdmin(db);
    const bank = db.banks.find((item) => item.id === decodeURIComponent(adminBankDetailMatch[1]!));
    if (!bank) {
      fail(404, 40401, "题库不存在");
    }
    if (method === "GET") {
      return bank;
    }
    if (method === "PATCH") {
      const input = asObject(body);
      if (typeof input.name === "string") {
        if (input.name.trim() === "" || input.name.length > 128) {
          fail(400, 40000, "题库名称不合法");
        }
        bank.name = input.name.trim();
      }
      if (input.description !== undefined) {
        bank.description = input.description === null ? null : String(input.description);
      }
      if (input.visibility === "PUBLIC" || input.visibility === "PRIVATE") {
        bank.visibility = input.visibility;
      }
      if (typeof input.status === "string") {
        bank.status = input.status as BankView["status"];
      }
      const admin = requireAdmin(db);
      appendAudit(db, admin, "BANK_UPDATE", "BANK", bank.id, String(input.reason ?? "") || null);
      persistMockDb();
      return bank;
    }
    if (method === "DELETE") {
      const admin = requireAdmin(db);
      const reason = String(asObject(body).reason ?? "").trim();
      if (reason === "") {
        fail(400, 40000, "删除题库必须填写原因");
      }
      bank.status = "RECYCLED";
      appendAudit(db, admin, "BANK_DELETE", "BANK", bank.id, reason);
      persistMockDb();
      return null;
    }
  }

  const adminBankSentencesMatch = pathname.match(/^\/admin\/banks\/([^/]+)\/sentences$/);
  if (adminBankSentencesMatch && method === "GET") {
    requireAdmin(db);
    const bankId = decodeURIComponent(adminBankSentencesMatch[1]!);
    const keyword = (params.get("keyword") ?? "").trim().toLowerCase();
    const status = params.get("status");
    const filtered = db.sentences.filter((sentence) => {
      if (sentence.bankId !== bankId) {
        return false;
      }
      if (status && sentence.status !== status) {
        return false;
      }
      if (keyword && !`${sentence.zh} ${sentence.ref}`.toLowerCase().includes(keyword)) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    const result = paginate(filtered, page, size);
    return { ...result, items: result.items.map(toSentence) };
  }

  const sentenceStatusMatch = pathname.match(/^\/admin\/sentences\/([^/]+)\/status$/);
  if (sentenceStatusMatch && method === "PATCH") {
    const admin = requireAdmin(db);
    const parsed = sentenceStatusRequestSchema.safeParse(body);
    if (!parsed.success) {
      fail(400, 40000, parsed.error.issues[0]?.message ?? "请求参数不合法");
    }
    const sentence = db.sentences.find(
      (item) => item.id === decodeURIComponent(sentenceStatusMatch[1]!),
    );
    if (!sentence) {
      fail(404, 40402, "题目不存在");
    }
    sentence.status = parsed.data.status;
    sentence.deleted = parsed.data.status === "RECYCLED";
    const bank = db.banks.find((item) => item.id === sentence.bankId);
    if (bank) {
      bank.version += 1;
    }
    appendAudit(db, admin, "SENTENCE_STATUS", "SENTENCE", sentence.id, parsed.data.reason);
    persistMockDb();
    return toSentence(sentence);
  }

  if (pathname === "/admin/uploads" && method === "POST") {
    requireAdmin(db);
    if (!(body instanceof FormData)) {
      fail(400, 40001, "请以 multipart/form-data 上传文件");
    }
    const file = body.get("file");
    if (!(file instanceof File)) {
      fail(400, 40001, "缺少上传文件");
    }
    if (!/\.(csv|json)$/i.test(file.name)) {
      fail(415, 41500, "文件格式不受支持");
    }
    if (file.size > 5 * 1024 * 1024) {
      fail(413, 41300, "文件过大，无法上传");
    }
    // 行数按体量粗估（每行约 120 字节），只为驱动进度条演示。
    const totalRows = Math.max(3, Math.min(500, Math.round(file.size / 120)));
    const failedRows = totalRows >= 8 ? 2 : 0;
    // TODO(mock): 服务端导入切片落地后 bankId 参与真实归属；这里只接收并回显在批次数据上。
    const bankId = String(body.get("bankId") ?? "").trim() || null;
    const batch: MockUploadBatch = {
      batchId: `upload-${nextMockId()}`,
      originalFilename: file.name,
      bankId,
      sizeBytes: file.size,
      status: "PROCESSING",
      totalRows,
      processedRows: 0,
      successRows: 0,
      failedRows,
      errorMessage: null,
      createdAt: new Date().toISOString(),
    };
    db.uploads.unshift(batch);
    persistMockDb();
    return { batchId: batch.batchId, status: batch.status };
  }

  const uploadDetailMatch = pathname.match(/^\/admin\/uploads\/([^/]+)$/);
  if (uploadDetailMatch && method === "GET") {
    requireAdmin(db);
    const batch = db.uploads.find(
      (item) => item.batchId === decodeURIComponent(uploadDetailMatch[1] ?? ""),
    );
    if (!batch) {
      fail(404, 40404, "上传批次不存在");
    }
    return advanceUpload(batch);
  }

  const uploadResumeMatch = pathname.match(/^\/admin\/uploads\/([^/]+)\/resume$/);
  if (uploadResumeMatch && method === "POST") {
    requireAdmin(db);
    const batch = db.uploads.find(
      (item) => item.batchId === decodeURIComponent(uploadResumeMatch[1] ?? ""),
    );
    if (!batch) {
      fail(404, 40404, "上传批次不存在");
    }
    if (batch.status !== "PAUSED" && batch.status !== "FAILED") {
      return advanceUpload(batch);
    }
    batch.status = "PROCESSING";
    batch.errorMessage = null;
    persistMockDb();
    return toUploadBatchView(batch);
  }

  const uploadErrorsMatch = pathname.match(/^\/admin\/uploads\/([^/]+)\/errors$/);
  if (uploadErrorsMatch && method === "GET") {
    requireAdmin(db);
    const batch = db.uploads.find(
      (item) => item.batchId === decodeURIComponent(uploadErrorsMatch[1] ?? ""),
    );
    if (!batch) {
      fail(404, 40404, "上传批次不存在");
    }
    // 行级失败样例：按失败行数生成稳定占位，演示「查看失败原因」链路。
    const errors = Array.from({ length: batch.failedRows }, (_, index) => ({
      lineNumber: index + 1,
      reason: "语块 answers 拼接与参考译文不一致（演示数据）",
    }));
    return { errors };
  }

  if (pathname === "/admin/imports/preview" && method === "POST") {
    requireAdmin(db);
    if (!(body instanceof FormData)) {
      fail(400, 40000, "请使用 multipart/form-data 上传文件");
    }
    const file = body.get("file");
    const bankId = String(body.get("bankId") ?? "");
    const format = String(body.get("format") ?? "").toUpperCase();
    const bank = db.banks.find((item) => item.id === bankId);
    if (!bank) {
      fail(404, 40401, "题库不存在");
    }
    if (format !== "JSON" && format !== "CSV") {
      fail(400, 40000, "format 必须是 JSON 或 CSV");
    }
    if (!(file instanceof File)) {
      fail(400, 40000, "缺少文件");
    }
    if (file.size > 5 * 1024 * 1024) {
      fail(413, 41300, "文件超过 5MB 上限");
    }
    const extension = file.name.slice(file.name.lastIndexOf(".")).toLowerCase();
    if (![".json", ".csv"].includes(extension)) {
      fail(415, 41500, "仅支持 .json / .csv");
    }
    const text = await file.text();
    let rawRows: Array<Record<string, unknown>>;
    if (format === "JSON") {
      try {
        const parsedJson = JSON.parse(text) as unknown;
        rawRows = Array.isArray(parsedJson)
          ? (parsedJson as Array<Record<string, unknown>>)
          : Array.isArray((parsedJson as { items?: unknown }).items)
            ? (parsedJson as { items: Array<Record<string, unknown>> }).items
            : fail(400, 40001, "JSON 必须是数组或 { items: [] }");
      } catch (error) {
        if (error instanceof ApiError) {
          throw error;
        }
        fail(400, 40001, "JSON 解析失败");
      }
    } else {
      rawRows = parseCsvRows(text);
    }
    const rows: ImportRow[] = [];
    const rawMap: Record<string, unknown> = {};
    rawRows.forEach((raw, index) => {
      const rowNo = index + 1;
      const errors = validateImportRow(raw, rowNo);
      const externalKey =
        typeof raw.externalKey === "string"
          ? raw.externalKey
          : `s_${String(rowNo).padStart(4, "0")}`;
      rows.push({
        rowNo,
        externalKey,
        status: errors.length === 0 ? "VALID" : "INVALID",
        errors,
        sentenceId: null,
        sentenceVersionId: null,
      });
      rawMap[String(rowNo)] = raw;
    });
    const validRows = rows.filter((row) => row.status === "VALID").length;
    const invalidRows = rows.length - validRows;
    const batch: MockImportBatch = {
      batchId: crypto.randomUUID(),
      bankId: bank.id,
      format,
      originalFilename: file.name,
      sizeBytes: file.size,
      status: "PREVIEWED",
      totalRows: rows.length,
      validRows,
      invalidRows,
      importedRows: 0,
      createdAt: new Date().toISOString(),
      previewedAt: new Date().toISOString(),
      committedAt: null,
      rolledBackAt: null,
      errorSummary:
        invalidRows > 0
          ? {
              schemaVersion: 1,
              code: "ROW_VALIDATION_FAILED",
              message: `${invalidRows} 行未通过校验`,
              sampleRows: rows
                .filter((row) => row.status === "INVALID")
                .slice(0, 3)
                .map((row) => row.rowNo),
            }
          : null,
      rows,
      rawRows: rawMap,
    };
    db.imports.unshift(batch);
    persistMockDb();
    return {
      batchId: batch.batchId,
      bankId: batch.bankId,
      status: "PREVIEWED",
      format: batch.format,
      totalRows: batch.totalRows,
      validRows: batch.validRows,
      invalidRows: batch.invalidRows,
      rows: rows.filter((row) => row.status === "INVALID").slice(0, 100),
      errorSummary: batch.errorSummary,
    };
  }

  if (pathname === "/admin/imports" && method === "GET") {
    requireAdmin(db);
    const bankId = params.get("bankId");
    const status = params.get("status");
    const from = params.get("from");
    const to = params.get("to");
    const filtered = db.imports.filter((batch) => {
      if (bankId && batch.bankId !== bankId) {
        return false;
      }
      if (status && batch.status !== status) {
        return false;
      }
      if (!inDateRange(batch.createdAt, from, to)) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    const result = paginate(filtered, page, size);
    return { ...result, items: result.items.map(toImportBatchView) };
  }

  const importCommitMatch = pathname.match(/^\/admin\/imports\/([^/]+)\/commit$/);
  if (importCommitMatch && method === "POST") {
    const admin = requireAdmin(db);
    const batch = db.imports.find(
      (item) => item.batchId === decodeURIComponent(importCommitMatch[1]!),
    );
    if (!batch) {
      fail(404, 40405, "导入批次不存在");
    }
    if (batch.status === "COMMITTED") {
      return toImportBatchView(batch);
    }
    if (batch.status !== "PREVIEWED") {
      fail(409, 40904, "当前批次不可提交");
    }
    const bank = db.banks.find((item) => item.id === batch.bankId);
    if (!bank) {
      fail(404, 40401, "题库不存在");
    }
    let imported = 0;
    for (const row of batch.rows) {
      if (row.status !== "VALID") {
        continue;
      }
      const raw = asObject(batch.rawRows[String(row.rowNo)]);
      bank.version += 1;
      const sentence = sentenceFromImport(raw, bank.id, bank.version);
      db.sentences.push(sentence);
      row.status = "IMPORTED";
      row.sentenceId = sentence.id;
      row.sentenceVersionId = sentence.sentenceVersionId;
      imported += 1;
    }
    bank.sentenceCount = db.sentences.filter(
      (sentence) => sentence.bankId === bank.id && !sentence.deleted,
    ).length;
    batch.status = "COMMITTED";
    batch.importedRows = imported;
    batch.committedAt = new Date().toISOString();
    appendAudit(
      db,
      admin,
      "IMPORT_COMMIT",
      "IMPORT_BATCH",
      batch.batchId,
      String(asObject(body).reason ?? "") || null,
    );
    persistMockDb();
    return toImportBatchView(batch);
  }

  const importRollbackMatch = pathname.match(/^\/admin\/imports\/([^/]+)\/rollback$/);
  if (importRollbackMatch && method === "POST") {
    const admin = requireAdmin(db);
    const batch = db.imports.find(
      (item) => item.batchId === decodeURIComponent(importRollbackMatch[1]!),
    );
    if (!batch) {
      fail(404, 40405, "导入批次不存在");
    }
    const reason = String(asObject(body).reason ?? "").trim();
    if (reason === "") {
      fail(400, 40000, "回滚必须填写原因");
    }
    if (batch.status === "ROLLED_BACK") {
      return toImportBatchView(batch);
    }
    if (batch.status !== "COMMITTED") {
      fail(409, 40904, "当前批次不可回滚");
    }
    const bank = db.banks.find((item) => item.id === batch.bankId);
    for (const row of batch.rows) {
      if (row.sentenceId) {
        const sentence = db.sentences.find((item) => item.id === row.sentenceId);
        if (sentence) {
          sentence.status = "RECYCLED";
          sentence.deleted = true;
          if (bank) {
            bank.version += 1;
            sentence.version = bank.version;
          }
        }
      }
    }
    if (bank) {
      bank.sentenceCount = db.sentences.filter(
        (sentence) => sentence.bankId === bank.id && !sentence.deleted,
      ).length;
    }
    batch.status = "ROLLED_BACK";
    batch.rolledBackAt = new Date().toISOString();
    appendAudit(db, admin, "IMPORT_ROLLBACK", "IMPORT_BATCH", batch.batchId, reason);
    persistMockDb();
    return toImportBatchView(batch);
  }

  const importDetailMatch = pathname.match(/^\/admin\/imports\/([^/]+)$/);
  if (importDetailMatch && method === "GET") {
    requireAdmin(db);
    const batch = db.imports.find(
      (item) => item.batchId === decodeURIComponent(importDetailMatch[1]!),
    );
    if (!batch) {
      fail(404, 40405, "导入批次不存在");
    }
    const status = params.get("status");
    const filtered = status ? batch.rows.filter((row) => row.status === status) : batch.rows;
    const { page, size } = pagination(params);
    return {
      batch: {
        ...toImportBatchView(batch),
        format: batch.format,
        originalFilename: batch.originalFilename,
        createdAt: batch.createdAt,
        previewedAt: batch.previewedAt,
        committedAt: batch.committedAt,
        errorSummary: batch.errorSummary,
      },
      rows: paginate(filtered, page, size),
    };
  }

  const rawAttemptMatch = pathname.match(/^\/admin\/attempts\/([^/]+)\/raw$/);
  if (rawAttemptMatch && method === "POST") {
    const admin = requireAdmin(db);
    const reason = String(asObject(body).reason ?? "").trim();
    if (reason === "") {
      fail(400, 40000, "查看答题原文必须填写原因");
    }
    const attempt = db.attempts.find(
      (item) => item.request.attemptId === decodeURIComponent(rawAttemptMatch[1]!),
    );
    if (!attempt) {
      fail(404, 40403, "答题记录不存在");
    }
    const auditId = nextMockId();
    db.auditLogs.unshift({
      id: auditId,
      module: "admin",
      actorId: admin.id,
      actorName: admin.username,
      actorRole: admin.role,
      clientIp: "127.0.0.1",
      action: "VIEW_ATTEMPT_RAW",
      targetType: "ATTEMPT",
      targetId: attempt.request.attemptId,
      // TODO(mock): 演示数据，真实接口落地后删除——审计行的人类可读对象名。
      targetLabel: targetLabelOf(db, "ATTEMPT", attempt.request.attemptId),
      reason,
      outcome: "SUCCESS",
      durationMs: 9,
      createdAt: new Date().toISOString(),
    });
    persistMockDb();
    return {
      attemptId: attempt.request.attemptId,
      userId: attempt.userId,
      sentenceId: attempt.request.sentenceId,
      answer: attempt.request.answer,
      grading: {
        id: attempt.response.grading.id,
        score: attempt.response.score,
        result: attempt.response.result,
      },
      auditId,
    };
  }

  // ---- 管理端九页签演示：概览聚合 ----
  if (pathname === "/admin/overview" && method === "GET") {
    requireAdmin(db);
    // 「游客」是未登录演示回退身份，不计入运营指标。
    const realUsers = db.users.filter((user) => !user.deleted && user.id !== "guest");
    const today = localDateString(new Date(), "Asia/Shanghai");
    const activeToday = realUsers.filter(
      (user) =>
        user.lastLoginAt !== null &&
        localDateString(new Date(user.lastLoginAt), "Asia/Shanghai") === today,
    ).length;
    const totalAttempts = db.attempts.length;
    const active7d = Array.from({ length: 7 }, (_, index) => {
      const key = localDateString(new Date(Date.now() - (6 - index) * 86_400_000), "Asia/Shanghai");
      return {
        date: key,
        count: db.attempts.filter(
          (attempt) => localDateString(new Date(attempt.createdAt), "Asia/Shanghai") === key,
        ).length,
      };
    });
    const pendingBatches = db.imports.filter((batch) => batch.status === "PREVIEWED");
    const disabledUsers = realUsers.filter((user) => !user.enabled);
    const pendingAppeals = db.appeals.filter((appeal) => appeal.status === "PENDING");
    const todos: Array<{ id: string; title: string; meta: string; href: string }> = [];
    if (pendingBatches.length > 0) {
      todos.push({
        id: "imports",
        title: `${pendingBatches.length} 条导入批次待提交`,
        meta: `来自「${pendingBatches[0]?.originalFilename ?? "导入文件"}」`,
        href: "/admin/generation",
      });
    }
    if (disabledUsers.length > 0) {
      todos.push({
        id: "disabled",
        title: `${disabledUsers.map((user) => user.username).join("、")}的账号被停用`,
        meta: "停用原因已写入审计 · 可恢复",
        href: "/admin/users",
      });
    }
    if (pendingAppeals.length > 0) {
      todos.push({
        id: "appeals",
        title: `${pendingAppeals.length} 条答案申诉待裁决`,
        meta: "在用户抽屉查看原文，填写原因后裁决",
        href: "/admin/users",
      });
    }
    const userName = (id: string) =>
      db.users.find((user) => user.id === id)?.username ?? "未知用户";
    const activity = [...db.attempts]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .slice(0, 5)
      .map((attempt) => ({
        id: attempt.request.attemptId,
        who: userName(attempt.userId),
        what: `${attempt.request.mode === "FREE" ? "整句" : "语块"}练习 · ${
          attempt.response.score === null ? "已看答案" : `${attempt.response.score} 分`
        }`,
        at: attempt.createdAt,
      }));
    return {
      metrics: {
        totalUsers: realUsers.length,
        activeToday,
        totalAttempts,
        estimatedCost: Math.round(totalAttempts * 0.006 * 100) / 100,
      },
      active7d,
      todos,
      activity,
    };
  }

  // ---- 管理端九页签演示：邀请码 ----
  if (pathname === "/admin/invites" && method === "GET") {
    requireAdmin(db);
    return { items: [...db.invites].reverse() };
  }

  if (pathname === "/admin/invites" && method === "POST") {
    const admin = requireAdmin(db);
    const code = `YL-${Math.random().toString(36).slice(2, 6).toUpperCase()}`;
    const invite = {
      code,
      used: false,
      usedBy: null,
      createdAt: new Date().toISOString(),
    };
    db.invites.unshift(invite);
    appendAudit(db, admin, "INVITE_CREATE", "INVITE", code, null);
    persistMockDb();
    return invite;
  }

  // ---- 管理端九页签演示：跨题库题目列表 ----
  if (pathname === "/admin/sentences" && method === "GET") {
    requireAdmin(db);
    const keyword = (params.get("keyword") ?? "").trim().toLowerCase();
    const bankId = params.get("bankId");
    const level = params.get("level");
    const status = params.get("status");
    const filtered = db.sentences.filter((sentence) => {
      if (bankId && sentence.bankId !== bankId) {
        return false;
      }
      if (level && sentence.level !== level) {
        return false;
      }
      // 未指定状态时隐藏回收站，与原型默认视图一致；回收站要显式按状态查。
      if (status) {
        if (sentence.status !== status) {
          return false;
        }
      } else if (sentence.status === "RECYCLED") {
        return false;
      }
      if (keyword && !`${sentence.zh} ${sentence.ref}`.toLowerCase().includes(keyword)) {
        return false;
      }
      return true;
    });
    const { page, size } = pagination(params);
    const result = paginate(filtered, page, size);
    return { ...result, items: result.items.map(toAdminSentence) };
  }

  if (pathname === "/admin/sentences/bulk-status" && method === "POST") {
    const admin = requireAdmin(db);
    const input = asObject(body);
    const ids = Array.isArray(input.ids)
      ? input.ids.filter((value): value is string => typeof value === "string")
      : [];
    const status = String(input.status ?? "");
    const reason = String(input.reason ?? "").trim();
    if (ids.length === 0) {
      fail(400, 40000, "请先选择句子");
    }
    if (!CONTENT_STATUSES.includes(status as ContentStatus)) {
      fail(400, 40000, "状态不合法");
    }
    if (reason === "") {
      fail(400, 40000, "批量操作必须填写原因");
    }
    let updated = 0;
    const touchedBanks = new Set<string>();
    for (const id of ids) {
      const sentence = db.sentences.find((item) => item.id === id);
      if (!sentence) {
        continue;
      }
      sentence.status = status as MockSentence["status"];
      sentence.deleted = status === "RECYCLED";
      touchedBanks.add(sentence.bankId);
      updated += 1;
    }
    for (const bankId of touchedBanks) {
      const bank = db.banks.find((item) => item.id === bankId);
      if (bank) {
        bank.version += 1;
      }
    }
    appendAudit(db, admin, "SENTENCE_BULK_STATUS", "SENTENCE", `${updated} 句 · ${status}`, reason);
    persistMockDb();
    return { updated };
  }

  if (pathname === "/admin/sentences/bulk-move" && method === "POST") {
    const admin = requireAdmin(db);
    const input = asObject(body);
    const ids = Array.isArray(input.ids)
      ? input.ids.filter((value): value is string => typeof value === "string")
      : [];
    const targetBankId = String(input.bankId ?? "");
    const reason = String(input.reason ?? "").trim();
    if (ids.length === 0) {
      fail(400, 40000, "请先选择句子");
    }
    const targetBank = db.banks.find((item) => item.id === targetBankId);
    if (!targetBank) {
      fail(404, 40401, "目标题库不存在");
    }
    if (reason === "") {
      fail(400, 40000, "批量移动必须填写原因");
    }
    let moved = 0;
    const touchedBanks = new Set<string>([targetBankId]);
    for (const id of ids) {
      const sentence = db.sentences.find((item) => item.id === id);
      if (!sentence || sentence.bankId === targetBankId) {
        continue;
      }
      touchedBanks.add(sentence.bankId);
      sentence.bankId = targetBankId;
      moved += 1;
    }
    // 只有真正发生移动的题库才推进版本号，保持题库版本与内容变更一一对应。
    for (const bankId of touchedBanks) {
      const bank = db.banks.find((item) => item.id === bankId);
      if (bank) {
        bank.version += 1;
      }
    }
    appendAudit(
      db,
      admin,
      "SENTENCE_BULK_MOVE",
      "SENTENCE",
      `${moved} 句 → ${targetBank.name}`,
      reason,
    );
    persistMockDb();
    return { moved };
  }

  const sentenceUpdateMatch = pathname.match(/^\/admin\/sentences\/([^/]+)$/);
  if (sentenceUpdateMatch && method === "PATCH") {
    const admin = requireAdmin(db);
    const sentence = db.sentences.find(
      (item) => item.id === decodeURIComponent(sentenceUpdateMatch[1]!),
    );
    if (!sentence) {
      fail(404, 40402, "题目不存在");
    }
    const input = asObject(body);
    if (typeof input.zh === "string") {
      const zh = input.zh.trim();
      if (zh.length < 4) {
        fail(400, 40000, "中文太短");
      }
      sentence.zh = zh;
    }
    if (typeof input.level === "string") {
      sentence.level = input.level;
    }
    if (Array.isArray(input.tags)) {
      sentence.tags = input.tags.filter((tag): tag is string => typeof tag === "string");
    }
    if (typeof input.note === "string") {
      sentence.note = input.note.trim();
    }
    if (typeof input.bankId === "string") {
      const bank = db.banks.find((item) => item.id === input.bankId);
      if (!bank) {
        fail(404, 40401, "题库不存在");
      }
      sentence.bankId = input.bankId;
    }
    if (
      typeof input.status === "string" &&
      CONTENT_STATUSES.includes(input.status as ContentStatus)
    ) {
      sentence.status = input.status as MockSentence["status"];
      sentence.deleted = input.status === "RECYCLED";
    }
    appendAudit(
      db,
      admin,
      "SENTENCE_UPDATE",
      "SENTENCE",
      sentence.id,
      String(input.reason ?? "") || null,
    );
    persistMockDb();
    return toAdminSentence(sentence);
  }

  // ---- 管理端九页签演示：AI 生成导入（不调用真实模型，写入由粘贴校验后的 JSON 驱动） ----
  if (pathname === "/admin/generation/import" && method === "POST") {
    const admin = requireAdmin(db);
    const input = asObject(body);
    const bank = db.banks.find((item) => item.id === String(input.bankId ?? ""));
    if (!bank) {
      fail(404, 40401, "题库不存在");
    }
    const items = Array.isArray(input.items) ? input.items : [];
    if (items.length === 0) {
      fail(400, 40000, "没有可导入的句子");
    }
    const rows: ImportRow[] = [];
    const rawMap: Record<string, unknown> = {};
    items.forEach((item, index) => {
      const raw = asObject(item);
      bank.version += 1;
      const sentence = sentenceFromImport(raw, bank.id, bank.version);
      db.sentences.push(sentence);
      const rowNo = index + 1;
      rows.push({
        rowNo,
        externalKey: `gen_${String(rowNo).padStart(4, "0")}`,
        status: "IMPORTED",
        errors: [],
        sentenceId: sentence.id,
        sentenceVersionId: sentence.sentenceVersionId,
      });
      rawMap[String(rowNo)] = raw;
    });
    bank.sentenceCount = db.sentences.filter(
      (sentence) => sentence.bankId === bank.id && !sentence.deleted,
    ).length;
    const now = new Date().toISOString();
    const batch: MockImportBatch = {
      batchId: crypto.randomUUID(),
      bankId: bank.id,
      format: "JSON",
      originalFilename: "AI 生成.json",
      sizeBytes: JSON.stringify(items).length,
      status: "COMMITTED",
      totalRows: rows.length,
      validRows: rows.length,
      invalidRows: 0,
      importedRows: rows.length,
      createdAt: now,
      previewedAt: now,
      committedAt: now,
      rolledBackAt: null,
      errorSummary: null,
      rows,
      rawRows: rawMap,
    };
    db.imports.unshift(batch);
    appendAudit(
      db,
      admin,
      "IMPORT_COMMIT",
      "IMPORT_BATCH",
      batch.batchId,
      String(input.reason ?? "") || "AI 生成导入",
    );
    persistMockDb();
    return toImportBatchView(batch);
  }

  // ---- 管理端九页签演示：用户抽屉的练习记录与指标 ----
  const userAttemptsMatch = pathname.match(/^\/admin\/users\/([^/]+)\/attempts$/);
  if (userAttemptsMatch && method === "GET") {
    requireAdmin(db);
    const userId = decodeURIComponent(userAttemptsMatch[1]!);
    if (!db.users.some((user) => user.id === userId)) {
      fail(404, 40400, "请求的资源不存在");
    }
    const attempts = db.attempts.filter((attempt) => attempt.userId === userId);
    const scored = attempts.filter((attempt) => attempt.response.score !== null);
    const avgScore =
      scored.length === 0
        ? null
        : Math.round(
            scored.reduce((sum, attempt) => sum + (attempt.response.score ?? 0), 0) / scored.length,
          );
    const dayKeys = new Set(
      attempts.map((attempt) => localDateString(new Date(attempt.createdAt), "Asia/Shanghai")),
    );
    // 连续天数：从今天向前数有记录的日界；今天没练时从昨天起算，避免刚到新一天就归零。
    let streakDays = 0;
    for (let offset = 0; offset < 365; offset += 1) {
      const key = localDateString(new Date(Date.now() - offset * 86_400_000), "Asia/Shanghai");
      if (dayKeys.has(key)) {
        streakDays += 1;
      } else if (offset > 0) {
        break;
      }
    }
    const activeDays30 = new Set(
      attempts
        .filter((attempt) => new Date(attempt.createdAt).getTime() >= Date.now() - 30 * 86_400_000)
        .map((attempt) => localDateString(new Date(attempt.createdAt), "Asia/Shanghai")),
    ).size;
    const items = [...attempts]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .slice(0, 8)
      .map((attempt) => ({
        attemptId: attempt.request.attemptId,
        sentenceId: attempt.request.sentenceId,
        zh: attempt.zh,
        ref: attempt.response.reference?.ref ?? null,
        mode: attempt.request.mode,
        kind: attempt.request.kind,
        score: attempt.response.score,
        createdAt: attempt.createdAt,
      }));
    // 近 30 天热区：按日作答数，供用户抽屉的 heat 条渲染。
    const heat = Array.from({ length: 30 }, (_, index) => {
      const key = localDateString(
        new Date(Date.now() - (29 - index) * 86_400_000),
        "Asia/Shanghai",
      );
      return attempts.filter(
        (attempt) => localDateString(new Date(attempt.createdAt), "Asia/Shanghai") === key,
      ).length;
    });
    return {
      metrics: { totalAttempts: attempts.length, avgScore, activeDays30, streakDays },
      heat,
      items,
    };
  }

  const userDeleteMatch = pathname.match(/^\/admin\/users\/([^/]+)$/);
  if (userDeleteMatch && method === "DELETE") {
    const admin = requireAdmin(db);
    const target = db.users.find((user) => user.id === decodeURIComponent(userDeleteMatch[1]!));
    if (!target) {
      fail(404, 40400, "请求的资源不存在");
    }
    if (target.id === admin.id) {
      fail(409, 40900, "不能删除自己的账号");
    }
    const reason = String(asObject(body).reason ?? "").trim();
    if (reason === "") {
      fail(400, 40000, "删除账号必须填写原因");
    }
    if (target.role === "ADMIN") {
      const activeAdmins = db.users.filter(
        (user) => user.role === "ADMIN" && user.enabled && !user.deleted,
      );
      if (activeAdmins.length <= 1) {
        fail(409, 40900, "不能删除最后一个管理员");
      }
    }
    // 软删：答题记录与审计只增不改，账号标记 deleted 后不再可见。
    target.deleted = true;
    target.enabled = false;
    appendAudit(db, admin, "USER_DELETE", "USER", target.id, reason);
    persistMockDb();
    return toAdminUser(target);
  }

  // ---- 管理端九页签演示：演示动作的通用审计入口（重置密码/导出数据/模型配置/运维吊销等） ----
  if (pathname === "/admin/audit-events" && method === "POST") {
    const admin = requireAdmin(db);
    const input = asObject(body);
    const action = String(input.action ?? "").trim();
    if (action === "") {
      fail(400, 40000, "缺少审计动作");
    }
    appendAudit(
      db,
      admin,
      action,
      input.targetType === undefined ? null : String(input.targetType),
      input.targetId === undefined ? null : String(input.targetId),
      input.reason ? String(input.reason) : null,
    );
    persistMockDb();
    return { id: db.auditLogs[0]?.id ?? "" };
  }

  return fail(404, 40400, "请求的资源不存在");
}

/**
 * 读取 mock 统计演示数据；V1 契约没有 14 天趋势与最近记录端点，这两块只在 mock 模式展示。
 * @returns 趋势与最近记录
 */
export function getMockStatsDemo(): {
  dailyTrend: Array<{ date: string; attempts: number; correctRate: number }>;
  recent: Array<{
    attemptId: string;
    sentenceId: string;
    zh: string;
    mode: "CLOZE" | "FREE";
    kind: "FIRST" | "RETRY" | "REVEAL";
    score: number | null;
    createdAt: string;
  }>;
} {
  const db = getMockDb();
  const user = currentUser(db);
  const attempts = user
    ? db.attempts.filter((attempt) => attempt.userId === user.id)
    : db.attempts.filter((attempt) => attempt.userId === "2");
  const dailyTrend = Array.from({ length: 14 }, (_, index) => {
    const date = new Date(Date.now() - (13 - index) * 86_400_000);
    const key = localDateString(date, "Asia/Shanghai");
    const dayAttempts = attempts.filter(
      (attempt) => localDateString(new Date(attempt.createdAt), "Asia/Shanghai") === key,
    );
    const scored = dayAttempts.filter((attempt) => attempt.response.score !== null);
    const correct = scored.filter((attempt) => attempt.response.score === 100).length;
    return {
      date: key,
      attempts: dayAttempts.length,
      correctRate: scored.length === 0 ? 0 : Math.round((correct / scored.length) * 1000) / 10,
    };
  });
  const recent = [...attempts]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, 8)
    .map((attempt) => ({
      attemptId: attempt.request.attemptId,
      sentenceId: attempt.request.sentenceId,
      zh: attempt.zh,
      mode: attempt.request.mode,
      kind: attempt.request.kind,
      score: attempt.response.score,
      createdAt: attempt.createdAt,
    }));
  return { dailyTrend, recent };
}
