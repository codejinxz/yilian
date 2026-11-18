import { apiRequest } from "@/lib/api";
import { isTransportError } from "@/lib/api-error";
import {
  type AppealResponse,
  type AttemptRequest,
  type AttemptResponse,
  appealResponseSchema,
  attemptRequestSchema,
  attemptResponseSchema,
  type ModelReview,
  modelReviewSchema,
  type NextPractice,
  nextPracticeSchema,
  type OverloadView,
  overloadViewSchema,
  type PostponeView,
  postponeViewSchema,
  type Review,
  reviewPageSchema,
  reviewSchema,
  type Today,
  type TutorAskResponse,
  todaySchema,
  tutorAskResponseSchema,
  wrongQuestionPageSchema,
} from "@/lib/schemas/practice";
import { jsonRequest, parseData, toQueryString } from "./shared";

/**
 * 答疑请求的超时毫秒数：覆盖服务端整条降级链路（模型调用 15s + 网关 429 冷却退避
 * 30s 重试一次 ≈ 31.5s）再留余量；默认 10s 会在服务端仍重试时先行掐断。
 */
export const ASK_TIMEOUT_MS = 35_000;

/**
 * 模型复核请求的超时毫秒数：与答疑同口径（服务端模型调用 15s + 解析失败重试一次，
 * 整链约 31.5s），默认 10s 会在服务端还在重试时先把用户侧掐断。
 */
export const REVIEW_TIMEOUT_MS = 35_000;

/**
 * 申诉提交请求的超时毫秒数：与复核/答疑同算术（服务端申诉落库后**同步**调 LLM 初裁，
 * 15s + 解析失败重试一次 ≈ 31.5s）。与复核链路同构对齐：默认 10s 会在
 * 服务端仍在初裁时先把用户侧掐断，而申诉行此时已落库——用户重试只会撞 40900「请勿重复
 * 提交」，失败提示与真实状态不符。
 */
export const APPEAL_TIMEOUT_MS = 35_000;

/**
 * 取下一题；没有可练题时返回 null（HTTP 200，页面显示空状态）。
 * sentenceId 指定句子时忽略 bankId/tag/exclude——复习页「重练」与下一步训练都依赖这一优先级。
 * errors=1 时改为只在错题里取下一题（今日页「错题」chip）；本地引擎与 mock 应答，不依赖网络。
 * @param params 题库、模式、指定句、考点筛选、错题开关与排除题
 * @param signal 取消信号
 * @returns 下一题或 null
 */
export async function getNextPractice(
  params: {
    bankId?: string;
    mode?: "CLOZE" | "FREE";
    sentenceId?: string;
    tag?: string;
    excludeIds?: string[];
    /** 旧的单题排除参数；等价于 excludeIds 里的一个元素，保留以防旧调用方失效。 */
    excludeSentenceId?: string;
    /** 只在错题里取下一题；与 sentenceId 同用时指定句优先（跳句是显式动作，不受错题池约束）。 */
    errors?: boolean;
  } = {},
  signal?: AbortSignal,
): Promise<NextPractice | null> {
  const query: Record<string, string | number | boolean> = {};
  if (params.bankId !== undefined) {
    query.bankId = params.bankId;
  }
  if (params.mode !== undefined) {
    query.mode = params.mode;
  }
  if (params.sentenceId !== undefined) {
    query.sentenceId = params.sentenceId;
  }
  if (params.tag !== undefined) {
    query.tag = params.tag;
  }
  if (params.errors) {
    query.errors = "1";
  }
  if (params.excludeSentenceId !== undefined) {
    query.excludeSentenceId = params.excludeSentenceId;
  }
  // excludeIds 是数组，toQueryString 只接受标量值；合并进查询串并去重旧的单题排除参数。
  const excludeIds = (params.excludeIds ?? [])
    .map((id) => id.trim())
    .filter((id) => id.length > 0 && id !== params.excludeSentenceId);
  const parts = new URLSearchParams(toQueryString(query).replace(/^\?/, ""));
  if (excludeIds.length > 0) {
    parts.set("excludeIds", excludeIds.join(","));
  }
  const serialized = parts.toString();
  const data = await apiRequest<unknown>(`/practice/next${serialized ? `?${serialized}` : ""}`, {
    signal,
  });
  if (data === null) {
    return null;
  }
  return parseData(nextPracticeSchema, data);
}

/** 传输失败后的重试等待；给网络抖动与服务端收尾一个恢复窗口，不放大重试次数。 */
const SUBMIT_RETRY_DELAY_MS = 800;

/**
 * 等待固定毫秒；重试退避用。
 * @param ms 等待毫秒数
 */
function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

/**
 * 提交一次作答；填空分数由页面本地计算并展示，服务端按版本复核。
 *
 * <p>根因防御：超时/连接重置（transport 失败）时请求可能已被服务端处理、
 * 只是响应没回来。这里在浏览器仍报在线时用同一载荷重试一次——attemptId 就是幂等键，
 * 服务端以 replayed=true 去重，重试安全；重试成功即拿到真实判分，不会误入离线队列。
 * 仅 transport 失败重试：response 类失败说明服务端已 2xx，重试只会复制同样的解析错误。
 *
 * @param input attempt 请求
 * @returns 判分结果、批注、参考译文与复习状态
 * @throws ApiError 首次与重试都传输失败，或业务/结构失败
 */
export async function submitAttempt(input: AttemptRequest): Promise<AttemptResponse> {
  const validated = attemptRequestSchema.parse(input);
  const post = () =>
    jsonRequest<unknown>("/practice/attempts", "POST", validated).then((data) =>
      parseData(attemptResponseSchema, data),
    );
  try {
    return await post();
  } catch (error) {
    if (!isTransportError(error) || !navigator.onLine) {
      throw error;
    }
    await delay(SUBMIT_RETRY_DELAY_MS);
    return post();
  }
}

/**
 * 读取答题结果详情；刷新页面后用它恢复批改页面。
 * @param attemptId attempt UUID
 * @param signal 取消信号
 * @returns 判分结果
 */
export async function getAttempt(
  attemptId: string,
  signal?: AbortSignal,
): Promise<AttemptResponse> {
  const data = await apiRequest<unknown>(`/practice/attempts/${encodeURIComponent(attemptId)}`, {
    signal,
  });
  return parseData(attemptResponseSchema, data);
}

/**
 * 提交「我觉得也对」申诉；落库后同步 LLM 初裁，回执带 AI 意见（不可用或未生成时为空）。
 * 超时放宽到 35s，与复核/答疑同算术（服务端同步初裁 15s + 重试一次 ≈ 31.5s）；
 * 默认 10s 会在初裁仍进行时先掐断，申诉实际已落库，重试只会撞 40900。
 * @param attemptId attempt id
 * @param key 批注稳定键
 * @param input 建议答案与理由
 * @returns 申诉状态
 */
export async function appealAnnotation(
  attemptId: string,
  key: string,
  input: { proposedAnswer?: string; reason?: string },
): Promise<AppealResponse> {
  const data = await jsonRequest<unknown>(
    `/practice/attempts/${encodeURIComponent(attemptId)}/annotations/${encodeURIComponent(key)}/appeal`,
    "POST",
    input,
    { timeoutMs: APPEAL_TIMEOUT_MS },
  );
  return parseData(appealResponseSchema, data);
}

/**
 * 「不认同」该批注，立即从错题与统计中排除。
 * @param attemptId attempt id
 * @param key 批注稳定键
 * @param input 理由
 * @returns 排除结果
 */
export async function disputeAnnotation(
  attemptId: string,
  key: string,
  input: { reason?: string },
): Promise<AppealResponse> {
  const data = await jsonRequest<unknown>(
    `/practice/attempts/${encodeURIComponent(attemptId)}/annotations/${encodeURIComponent(key)}/dispute`,
    "POST",
    input,
  );
  return parseData(appealResponseSchema, data);
}

/**
 * 读取错题列表；默认过滤已被用户排除的批注。
 * @param params 分页、是否含已排除、题库与考点
 * @param signal 取消信号
 * @returns 分页错题
 */
export async function getWrongQuestions(
  params: {
    page?: number;
    size?: number;
    includeDisputed?: boolean;
    bankId?: string;
    tag?: string;
  } = {},
  signal?: AbortSignal,
) {
  const data = await apiRequest<unknown>(`/practice/wrong-questions${toQueryString(params)}`, {
    signal,
  });
  return parseData(wrongQuestionPageSchema, data);
}

/**
 * 读取复习队列；按 dueAt 升序。
 * @param params 分页、三档状态、模式与是否只看到期
 * @param signal 取消信号
 * @returns 分页复习卡
 */
export async function getReviews(
  params: {
    page?: number;
    size?: number;
    state?: "NEW" | "LEARNING" | "MASTERED";
    mode?: "CLOZE" | "FREE";
    due?: boolean;
  } = {},
  signal?: AbortSignal,
) {
  const data = await apiRequest<unknown>(`/practice/reviews${toQueryString(params)}`, { signal });
  return parseData(reviewPageSchema, data);
}

/**
 * 把复习卡重置为 NEW；保留历史日志。
 * @param reviewId 复习卡 id
 * @returns 更新后的复习卡
 */
export async function resetReview(reviewId: string): Promise<Review> {
  const data = await jsonRequest<unknown>(
    `/practice/reviews/${encodeURIComponent(reviewId)}/reset`,
    "POST",
    { reason: "想从头练" },
  );
  return parseData(reviewSchema, data);
}

/**
 * 读取今日启动台数字。
 * @param signal 取消信号
 * @returns 到期数、今日已练、目标、正确率与连续天数
 */
export async function getToday(signal?: AbortSignal): Promise<Today> {
  const data = await apiRequest<unknown>("/practice/today", { signal });
  return parseData(todaySchema, data);
}

/**
 * 读取积压降载状态；到期数超过阈值时客户端只推最紧急 pushLimit 道。
 * 游客模式由页面直连本地引擎（引擎阈值与推送上限与契约实现可能不同），本函数只在登录态调用。
 * @param signal 取消信号
 * @returns 降载视图（当前到期数、阈值、推送上限与是否降载）
 */
export async function getReviewOverload(signal?: AbortSignal): Promise<OverloadView> {
  const data = await apiRequest<unknown>("/practice/reviews/overload", { signal });
  return parseData(overloadViewSchema, data);
}

/**
 * 顺延到期卡；服务端保留最早到期的 5 道，其余顺延 3 天（对齐原型；所有顺延可见、重练即恢复）。
 * 游客模式由页面直连本地引擎，本函数只在登录态调用。
 * @param reason 顺延理由，可空；结构化日志记录
 * @returns 顺延张数与保留张数
 */
export async function postponeReviews(reason?: string): Promise<PostponeView> {
  const data = await jsonRequest<unknown>(
    "/practice/reviews/postpone",
    "POST",
    reason === undefined ? undefined : { reason },
  );
  return parseData(postponeViewSchema, data);
}

/**
 * 题目答疑（API §6.12）：就当前句一问一答；回答必带免责标注。
 * 未配置模型凭证返回 40400、答疑未开放返回 40304，调用方据 ApiError.code 分支处理。
 * 超时放宽到 35s：服务端模型调用自身 15s，网关 429 冷却退避 30s 重试一次后整条降级链路
 * 约 31.5s——默认 10s 会在服务端还在重试时先把用户侧掐断，永远等不到降级回复。
 * @param sentenceId 题目 id
 * @param question 用户问题（1-500 字符；服务端同样校验）
 * @returns 回答与免责标注
 */
export async function askTutor(sentenceId: string, question: string): Promise<TutorAskResponse> {
  const data = await jsonRequest<unknown>(
    `/practice/${encodeURIComponent(sentenceId)}/ask`,
    "POST",
    { question },
    { timeoutMs: ASK_TIMEOUT_MS },
  );
  return parseData(tutorAskResponseSchema, data);
}

/**
 * 模型复核（API §6.13）：对本人的已判分提交追加一条 grader=MODEL 的判分记录，不覆盖首判。
 * 同一提交幂等：重复请求返回既有复核结果，不重复计费；限流 5 次/分钟/账号（42900 + Retry-After）。
 * 未配置模型 40400、调用/输出解析失败（重试 1 次后仍败）50000 固定文案降级——两种失败都不落行，
 * 调用方保留原判分展示即可。超时放宽到 35s，与答疑同口径（服务端模型调用 15s + 重试一次）。
 * @param attemptId attempt UUID
 * @param signal 取消信号
 * @returns 复核结果（新分 + 理由 + 模型快照与耗时 + 首判分）
 */
export async function reviewAttempt(attemptId: string, signal?: AbortSignal): Promise<ModelReview> {
  const data = await apiRequest<unknown>(
    `/practice/attempts/${encodeURIComponent(attemptId)}/review`,
    { method: "POST", signal },
    { timeoutMs: REVIEW_TIMEOUT_MS },
  );
  return parseData(modelReviewSchema, data);
}
