import { z } from "zod";
import { ApiError } from "@/lib/api-error";
import { localDateString } from "@/lib/format";
import type { StoredReview } from "@/lib/local-engine";
import { isMockEnabled } from "@/lib/mock-flag";
import {
  type AuthResponse,
  authResponseSchema,
  type CurrentUser,
  currentUserSchema,
} from "@/lib/schemas/account";

export { ApiError } from "@/lib/api-error";
export type { AuthResponse, CurrentUser } from "@/lib/schemas/account";

const API_BASE = "/api";
const REQUEST_TIMEOUT_MS = 10_000;
const FALLBACK_ERROR_CODE = -1;

const apiResponseSchema = z.object({
  code: z.number(),
  message: z.string(),
  data: z.unknown().optional(),
});

type ApiEnvelope = z.infer<typeof apiResponseSchema>;

const csrfSchema = z.object({
  token: z.string(),
  headerName: z.string(),
  parameterName: z.string(),
});

/** 刷新会话的三种结果；anonymous 表示 refresh cookie 已失效。 */
type RefreshOutcome =
  | { kind: "ok"; auth: AuthResponse }
  | { kind: "anonymous" }
  | { kind: "error"; error: ApiError };

let accessToken: string | null = null;
let cachedCsrfToken: string | null = null;
let csrfPromise: Promise<string> | null = null;
let refreshPromise: Promise<RefreshOutcome> | null = null;
let unauthorizedHandler: () => void = () => {};

/**
 * 设置或清空内存中的 access token；不写入 localStorage/sessionStorage，刷新页面后由 refresh cookie 恢复会话。
 * @param token 新的 access token；传 null 表示清空
 */
export function setAccessToken(token: string | null): void {
  accessToken = token;
}

/**
 * 读取内存中的 access token；仅供冷启动判断与测试使用，业务请求由 request 自动附加。
 * @returns 当前 access token；未登录时为 null
 */
export function getAccessToken(): string | null {
  return accessToken;
}

/**
 * 测试与 HMR 使用：清空 token、CSRF 缓存和正在进行的刷新状态。
 */
export function resetApiState(): void {
  accessToken = null;
  cachedCsrfToken = null;
  csrfPromise = null;
  refreshPromise = null;
}

/**
 * 注册 401 后的全局会话失效处理；只应由 router 注入一次，避免测试/HMR 多 router 互相覆盖。
 * @param handler 清空本地会话并跳转登录页的回调
 */
export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler;
}

/**
 * 把以 `/api` 开头的相对路径解析为当前源的绝对 URL，供 fetch 使用。
 * @param path `/api` 之后的路径
 * @returns 当前源下的绝对 URL
 */
function absolute(path: string): URL {
  return new URL(path, window.location.origin);
}

/**
 * 按 HTTP 状态给出兜底中文提示；仅在响应没有可用的业务 message 时使用。
 * @param status HTTP 状态码
 * @returns 可直接展示的提示
 */
function statusMessage(status: number): string {
  if (status === 401) {
    return "请先登录";
  }
  if (status === 403) {
    return "没有访问权限";
  }
  if (status === 413) {
    return "文件过大，无法上传";
  }
  if (status === 415) {
    return "文件格式不受支持";
  }
  if (status === 429) {
    return "请求过于频繁，请稍后再试";
  }
  if (status >= 500) {
    return "服务器暂时无法处理请求，请稍后再试";
  }
  return `请求失败（${status}）`;
}

/**
 * 合并调用方 signal 与请求超时；使用 AbortController 而不是 AbortSignal.any，兼容 iOS 15 等较旧浏览器。
 * @param external 调用方 signal，可为空
 * @param timeoutMs 本次请求的超时毫秒数；默认 10 秒，上传接口会放宽
 * @returns 组合后的 signal 与清理函数
 */
function createTimeoutSignal(
  external?: AbortSignal | null,
  timeoutMs = REQUEST_TIMEOUT_MS,
): { signal: AbortSignal; dispose: () => void } {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => {
    controller.abort(new DOMException("请求超时", "TimeoutError"));
  }, timeoutMs);
  const onAbort = () => controller.abort(external?.reason);
  if (external) {
    if (external.aborted) {
      onAbort();
    } else {
      external.addEventListener("abort", onAbort, { once: true });
    }
  }
  return {
    signal: controller.signal,
    dispose: () => {
      window.clearTimeout(timeoutId);
      external?.removeEventListener("abort", onAbort);
    },
  };
}

/**
 * 把 fetch 抛出的异常归一为可展示的 ApiError；超时或中止提示重试，其余按网络失败处理。
 * @param error fetch 抛出的原始异常
 * @returns status 为 0 的 ApiError
 */
function toNetworkError(error: unknown): ApiError {
  if (
    error instanceof DOMException &&
    (error.name === "TimeoutError" || error.name === "AbortError")
  ) {
    return new ApiError(0, FALLBACK_ERROR_CODE, "请求超时，请重试");
  }
  if (error instanceof ApiError) {
    return error;
  }
  return new ApiError(0, FALLBACK_ERROR_CODE, "网络连接失败，请检查网络后重试");
}

/**
 * 尽力解析统一响应信封；响应不是 JSON 或结构不合法时返回 null，由调用方结合 HTTP 状态兜底。
 * @param response fetch 响应
 * @returns 合法信封或 null
 */
async function readApiEnvelope(response: Response): Promise<ApiEnvelope | null> {
  try {
    const parsed = apiResponseSchema.safeParse(await response.json());
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

/**
 * 把失败响应转换为 ApiError；业务码缺失时退回 HTTP 状态，429 额外读取 Retry-After。
 * @param response 失败响应
 * @param envelope 已解析的信封，可为 null
 * @returns 带 HTTP 状态与业务码的异常
 */
function toApiError(response: Response, envelope: ApiEnvelope | null): ApiError {
  const code = envelope && envelope.code !== 0 ? envelope.code : response.status;
  let message = envelope?.message || statusMessage(response.status);
  if (response.status === 429) {
    const retryAfter = response.headers.get("Retry-After");
    if (retryAfter) {
      message = `请求过于频繁，请 ${retryAfter} 秒后重试`;
    }
  }
  return new ApiError(response.status, code, message);
}

/**
 * 解包统一响应信封：HTTP 非 2xx、信封缺失或 code 非 0 都抛 ApiError，否则返回 data。
 * @param response fetch 响应
 * @param envelope 已解析的信封
 * @returns 信封中的 data
 * @throws ApiError 响应失败或信封不可用
 */
function unwrapApiEnvelope(response: Response, envelope: ApiEnvelope | null): unknown {
  if (!response.ok) {
    throw toApiError(response, envelope);
  }
  if (!envelope) {
    throw new ApiError(response.status, FALLBACK_ERROR_CODE, "服务响应异常，请稍后重试");
  }
  if (envelope.code !== 0) {
    throw toApiError(response, envelope);
  }
  return envelope.data;
}

/**
 * 校验并返回当前用户；结构不合法时按服务端响应异常处理，避免把脏数据写进 Query 缓存。
 * @param data 信封中的 data
 * @returns 通过校验的用户
 * @throws ApiError 数据结构不符合 currentUserSchema
 */
function parseUser(data: unknown): CurrentUser {
  const parsed = currentUserSchema.safeParse(data);
  if (!parsed.success) {
    throw new ApiError(0, FALLBACK_ERROR_CODE, "服务响应异常，请稍后重试");
  }
  return parsed.data;
}

/**
 * 读取 CSRF token：优先用内存缓存，其次解析 XSRF-TOKEN cookie，最后向服务端申请。
 * @returns 可放入 X-XSRF-TOKEN 请求头的 token
 */
async function readCsrfToken(): Promise<string> {
  // Cookie 是服务端当前令牌的权威来源：认证请求可能轮换 CSRF，不能优先返回旧内存缓存。
  const fromCookie = document.cookie
    .split("; ")
    .find((part) => part.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
  if (fromCookie) {
    try {
      cachedCsrfToken = decodeURIComponent(fromCookie);
      return cachedCsrfToken;
    } catch {
      // 畸形 cookie 直接丢掉，重新向服务端要一个新 token。
    }
  }
  if (cachedCsrfToken) {
    // 没有 cookie 时内存 token 不可能通过双提交校验，必须重新向服务端申请。
    cachedCsrfToken = null;
  }
  return fetchCsrfToken();
}

/**
 * 无条件向服务端申请 CSRF token；force=true 时先删除可读 cookie，确保服务端生成新 token。
 * 并发调用共享同一个请求，避免多个 Set-Cookie 互相覆盖。
 * @param force 是否强制轮换
 * @returns 新的 CSRF token
 * @throws ApiError 网络失败、HTTP 失败或响应结构不合法
 */
async function fetchCsrfToken(force = false): Promise<string> {
  if (force) {
    cachedCsrfToken = null;
    csrfPromise = null;
    // biome-ignore lint/suspicious/noDocumentCookie: 双提交 CSRF 必须清除可读 cookie；Cookie Store API 在目标浏览器不可用。
    document.cookie = "XSRF-TOKEN=; Max-Age=0; Path=/; SameSite=Strict";
  }
  if (csrfPromise) {
    return csrfPromise;
  }
  csrfPromise = (async () => {
    let response: Response;
    const requestSignal = createTimeoutSignal();
    try {
      response = await fetch(absolute(`${API_BASE}/auth/csrf`), {
        credentials: "include",
        headers: { Accept: "application/json" },
        signal: requestSignal.signal,
      });
    } catch (error) {
      throw toNetworkError(error);
    } finally {
      requestSignal.dispose();
    }
    const envelope = await readApiEnvelope(response);
    const parsed = csrfSchema.safeParse(unwrapApiEnvelope(response, envelope));
    if (!parsed.success) {
      throw new ApiError(response.status, FALLBACK_ERROR_CODE, "无法获取 CSRF 令牌");
    }
    cachedCsrfToken = parsed.data.token;
    return cachedCsrfToken;
  })();
  try {
    return await csrfPromise;
  } finally {
    csrfPromise = null;
  }
}

/**
 * 用当前 CSRF 发一次 refresh；40301 时强制换 token 再试一次。
 * @param csrf CSRF token
 * @returns 原始响应
 */
async function postRefresh(csrf: string): Promise<Response> {
  const requestSignal = createTimeoutSignal();
  try {
    return await fetch(absolute(`${API_BASE}/auth/refresh`), {
      method: "POST",
      credentials: "include",
      headers: { Accept: "application/json", "X-XSRF-TOKEN": csrf },
      signal: requestSignal.signal,
    });
  } finally {
    requestSignal.dispose();
  }
}

/**
 * 跨标签串行刷新；不支持 Web Locks 的环境退回单标签 single-flight。
 * @param task 刷新任务
 * @returns 刷新结果
 */
async function withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  const locks = (navigator as Navigator & { locks?: LockManager }).locks;
  if (!locks || typeof locks.request !== "function") {
    return task();
  }
  return locks.request("yilian-refresh", task) as Promise<T>;
}

/**
 * 合并并发刷新：同一时刻只发一次 refresh，成功时更新内存 access token；401 视为匿名，其余失败返回错误。
 * @returns 刷新结果：成功、匿名或错误
 */
async function refreshSession(): Promise<RefreshOutcome> {
  if (refreshPromise) {
    return refreshPromise;
  }
  refreshPromise = withRefreshLock(async () => {
    try {
      // 刷新是 cookie 认证；Cookie 是 CSRF 的权威来源。
      let csrf = await readCsrfToken();
      let response = await postRefresh(csrf);
      if (response.status === 403) {
        const csrfError = await readApiEnvelope(response);
        if (csrfError?.code !== 40301) {
          return { kind: "error", error: toApiError(response, csrfError) };
        }
        csrf = await fetchCsrfToken(true);
        response = await postRefresh(csrf);
      }
      if (response.status === 401) {
        return { kind: "anonymous" };
      }
      const envelope = await readApiEnvelope(response);
      if (!response.ok || !envelope || envelope.code !== 0) {
        return { kind: "error", error: toApiError(response, envelope) };
      }
      const parsed = authResponseSchema.safeParse(envelope.data);
      if (!parsed.success) {
        return {
          kind: "error",
          error: new ApiError(response.status, FALLBACK_ERROR_CODE, "服务响应异常，请稍后重试"),
        };
      }
      accessToken = parsed.data.accessToken;
      // 登录/刷新可能轮换 CSRF；清掉缓存，下一次非 GET 重新读取服务端 cookie。
      cachedCsrfToken = null;
      return { kind: "ok", auth: parsed.data };
    } catch (error) {
      if (error instanceof ApiError) {
        return { kind: "error", error };
      }
      return { kind: "error", error: new ApiError(0, FALLBACK_ERROR_CODE, "刷新登录状态失败") };
    } finally {
      refreshPromise = null;
    }
  });
  return refreshPromise;
}

/** request 的重试与跳转控制项；递归重放时用于避免死循环。 */
interface RequestOptions {
  /** 已刷新过一次 access token，禁止再次刷新。 */
  skipRefresh?: boolean;
  /** 401 时不触发全局登出跳转，由登录或刷新流程自行处理。 */
  skipUnauthorizedRedirect?: boolean;
  /** CSRF 已重新获取过一次，禁止再次重试。 */
  csrfRetried?: boolean;
  /** 覆盖本次请求的超时毫秒数；上传大文件需要比默认 10 秒更长的窗口。 */
  timeoutMs?: number;
}

/**
 * 发起 API 请求：自动附加 Bearer 与 CSRF；遇 401 合并刷新一次后重放，遇 40301 重取 CSRF 后再试一次。
 * 当 VITE_USE_MOCK=true 时改走内置 mock adapter，所有 planned 接口均可完整走通。
 * @param path `/api` 之后的路径
 * @param init fetch 初始化参数；method 缺省为 GET
 * @param options 刷新、跳转与 CSRF 重试的控制项
 * @returns 解包后的 data；204 返回 undefined
 * @throws ApiError 网络、超时、HTTP 或业务码失败
 */
async function request<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  if (isMockEnabled()) {
    const { handleMockRequest } = await import("@/mocks/handlers");
    const data = await handleMockRequest(path, init);
    return data as T;
  }
  if (isLocalRouteEnabled(path)) {
    return handleLocalRequest<T>(path, init);
  }

  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  // FormData 必须由浏览器生成 multipart boundary；手动写 Content-Type 会破坏上传。
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }
  if (method !== "GET" && method !== "HEAD") {
    headers.set("X-XSRF-TOKEN", await readCsrfToken());
  }

  let response: Response;
  const requestSignal = createTimeoutSignal(init.signal, options.timeoutMs);
  try {
    response = await fetch(absolute(`${API_BASE}${path}`), {
      ...init,
      method,
      headers,
      credentials: "include",
      signal: requestSignal.signal,
    });
  } catch (error) {
    throw toNetworkError(error);
  } finally {
    requestSignal.dispose();
  }

  const envelope = await readApiEnvelope(response);

  if (response.status === 401 && !options.skipRefresh) {
    const outcome = await refreshSession();
    if (outcome.kind === "ok") {
      return request<T>(path, init, { ...options, skipRefresh: true });
    }
    if (outcome.kind === "error") {
      throw outcome.error;
    }
  }

  // CSRF 失效时只重取一次 token；真正的权限 403 会由服务端继续返回 403。
  if (
    response.status === 403 &&
    !options.csrfRetried &&
    method !== "GET" &&
    method !== "HEAD" &&
    envelope?.code === 40301
  ) {
    try {
      await fetchCsrfToken(true);
      return request<T>(path, init, { ...options, csrfRetried: true });
    } catch {
      // 取不到新 CSRF token 时继续按原始 403 处理。
    }
  }

  if (response.status === 401 && !options.skipUnauthorizedRedirect) {
    unauthorizedHandler();
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return unwrapApiEnvelope(response, envelope) as T;
}

/**
 * 本地模式路由表：非 mock 环境下，由本地练习引擎应答的路径组。
 *
 * 游客不登录真实可用的架构决策要求练习/复习/统计/今日全部本地应答；
 * auth、me、settings、banks 永远走网络（banks 匿名可访问，失败时引擎用旧缓存降级）。
 * 服务端练习切片（#8）落地后，把对应组的 enabled 改为 false 即可整体切回网络应答——
 * 这是唯一的切换开关，不需要改动任何页面代码。
 */
const LOCAL_ROUTE_GROUPS: ReadonlyArray<{ prefix: string; enabled: boolean }> = [
  { prefix: "/practice", enabled: true },
  { prefix: "/analytics", enabled: true },
];

/**
 * 判断路径是否应由本地引擎应答；IndexedDB 不可用的环境（旧浏览器/隐私模式）不启用本地模式，
 * 让请求正常走网络并由服务端决定降级行为。
 * @param path `/api` 之后的路径（含查询串）
 * @returns 是否启用本地应答
 */
function isLocalRouteEnabled(path: string): boolean {
  if (typeof indexedDB === "undefined") {
    return false;
  }
  return LOCAL_ROUTE_GROUPS.some(
    (group) =>
      group.enabled &&
      (path === group.prefix ||
        path.startsWith(`${group.prefix}/`) ||
        path.startsWith(`${group.prefix}?`)),
  );
}

/**
 * 本地模式分页切片；页码越界回落最后一页，与 mock/服务端 PageResponse 语义一致。
 * @param items 全量条目
 * @param page 页码（从 1 开始）
 * @param size 每页条数
 * @returns PageResponse 形状
 */
function localPaginate<T>(
  items: T[],
  page: number,
  size: number,
): { items: T[]; page: number; size: number; total: number; totalPages: number; hasNext: boolean } {
  const total = items.length;
  const totalPages = Math.max(1, Math.ceil(total / size));
  const safePage = Math.min(Math.max(1, page), totalPages);
  const start = (safePage - 1) * size;
  return {
    items: items.slice(start, start + size),
    page: safePage,
    size,
    total,
    totalPages,
    hasNext: safePage < totalPages,
  };
}

/**
 * 解析分页参数；越界或非数字与 mock 一致返回 40004。
 * @param params 查询参数
 * @returns page/size
 */
function localPagination(params: URLSearchParams): { page: number; size: number } {
  const page = Number(params.get("page") ?? 1);
  const size = Number(params.get("size") ?? 20);
  if (!Number.isInteger(page) || page < 1 || !Number.isInteger(size) || size < 1 || size > 100) {
    throw new ApiError(400, 40004, "分页参数不合法");
  }
  return { page, size };
}

/**
 * 读取 JSON 请求体；本地路由的写请求都由 jsonRequest 发出，body 一定是 JSON 字符串。
 * @param init fetch 初始化
 * @returns 解析后的对象
 * @throws ApiError 请求体不是合法 JSON
 */
async function readLocalJsonBody(init: RequestInit): Promise<Record<string, unknown>> {
  if (typeof init.body !== "string") {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(init.body);
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    throw new ApiError(400, 40001, "请求体格式不正确");
  }
}

/**
 * 读取浏览器 IANA 时区；解析失败回退 UTC，保证 todaySchema 的 timezone 字段始终有值。
 * @returns IANA 时区名
 */
function localTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

/**
 * 非登录态的本地模式应答入口：把 practice/analytics 组的请求翻译成引擎调用。
 * 返回值与 mock 同构——request 的调用方拿到的是解包后的 data，
 * data 形状严格符合 lib/schemas 里对应的 Zod schema，parseData 才能通过。
 * @param path `/api` 之后的路径（含查询串）
 * @param init fetch 初始化
 * @returns 解包后的 data
 * @throws ApiError 路径未实现、记录不存在或引擎拒绝
 */
async function handleLocalRequest<T>(path: string, init: RequestInit): Promise<T> {
  const engine = await import("@/lib/local-engine");
  const method = (init.method ?? "GET").toUpperCase();
  const url = new URL(path, window.location.origin);
  const pathname = url.pathname;
  const params = url.searchParams;

  if (pathname === "/practice/next" && method === "GET") {
    const excludeIds = (params.get("excludeIds") ?? "")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
    const excludeSentenceId = params.get("excludeSentenceId");
    if (excludeSentenceId) {
      excludeIds.push(excludeSentenceId);
    }
    const engineSentenceId = params.get("sentenceId");
    // 复习页/今日页「重练」会带 sentenceId 指定这一句；指定句优先级最高，
    // 忽略 bankId/tag/exclude（句子的归属题库页面上不可知，跨题库召回必须全库查找）。
    // 找不到指定句时退回常规取题，避免死链把练习页打成空白。
    if (engineSentenceId) {
      const picked = (await engine.getCachedSentences()).find(
        (item) => item.id === engineSentenceId,
      );
      if (picked) {
        const queue = await engine.getReviewQueue();
        const record = queue.rows.find((row) => row.sentence.id === picked.id)?.review as
          | StoredReview
          | undefined;
        return {
          sentence: picked,
          review: record ? engine.reviewToApi(record) : null,
          selection: record ? "MANUAL" : "NEW",
        } as T;
      }
    }
    // 今日页「错题」chip（errors=1）：只在错题里取下一题，口径与错题列表一致
    // （最近一次判分 <100 或看答案；批注全被「不认同」的整题默认跳过）。
    // bankId/tag 是显式筛选需要尊重；mode 不过滤——错题是句子级召回，练习模式由页面当前选择决定。
    // 排序：dueAt 升序取最紧急；引擎的错题行已按「最近做错在前」排好，稳定排序保留同 dueAt 时的该顺序。
    if (params.get("errors") === "1") {
      const bankIdParam = params.get("bankId") ?? undefined;
      const tagParam = params.get("tag") ?? undefined;
      const excluded = new Set(excludeIds);
      // 引擎缓存的句子不携带 bankId，用「指定题库的句子集合」做归属过滤，等价于按题库筛选。
      const bankSentenceIds = bankIdParam
        ? new Set((await engine.getCachedSentences(bankIdParam)).map((item) => item.id))
        : null;
      const errorRows = (await engine.getWrongQuestions())
        .filter((row) => {
          if (excluded.has(row.sentence.id)) {
            return false;
          }
          if (bankSentenceIds && !bankSentenceIds.has(row.sentence.id)) {
            return false;
          }
          if (tagParam && !row.sentence.tags.includes(tagParam)) {
            return false;
          }
          if (row.annotations.length > 0 && row.annotations.every((a) => a.disputed)) {
            return false;
          }
          return true;
        })
        .sort((a, b) => a.dueAt.localeCompare(b.dueAt));
      const pickedRow = errorRows[0];
      if (!pickedRow) {
        return null as T;
      }
      const queue = await engine.getReviewQueue();
      const record = queue.rows.find((row) => row.sentence.id === pickedRow.sentence.id)?.review as
        | StoredReview
        | undefined;
      return {
        sentence: pickedRow.sentence,
        review: record ? engine.reviewToApi(record) : null,
        // 不改 nextPracticeSchema：错题召回是到期召回的变体（错题就在学习队列里），沿用 DUE。
        selection: "DUE",
      } as T;
    }
    const sentence = await engine.pickNext({
      bankId: params.get("bankId") ?? undefined,
      tag: params.get("tag") ?? undefined,
      mode:
        params.get("mode") === "CLOZE" || params.get("mode") === "FREE"
          ? (params.get("mode") as "CLOZE" | "FREE")
          : undefined,
      ...(excludeIds.length > 0 ? { excludeIds } : {}),
    });
    if (!sentence) {
      return null as T;
    }
    const queue = await engine.getReviewQueue();
    const record = queue.rows.find((row) => row.sentence.id === sentence.id)?.review as
      | StoredReview
      | undefined;
    return {
      sentence,
      review: record ? engine.reviewToApi(record) : null,
      // 到期卡按 dueAt 召回；题库顺序只取未学新题，因此 selection 只有 DUE/NEW 两种。
      selection: record?.state === "LEARNING" ? "DUE" : "NEW",
    } as T;
  }

  if (pathname === "/practice/attempts" && method === "POST") {
    const input = await readLocalJsonBody(init);
    return engine.submitLocalAttempt(input as Parameters<typeof engine.submitLocalAttempt>[0]) as T;
  }

  const attemptDetailMatch = pathname.match(/^\/practice\/attempts\/([^/]+)$/);
  if (attemptDetailMatch && method === "GET") {
    const attempt = await engine.getAttemptLocal(decodeURIComponent(attemptDetailMatch[1] ?? ""));
    if (!attempt) {
      throw new ApiError(404, 40403, "答题记录不存在");
    }
    return attempt as T;
  }

  const appealMatch = pathname.match(
    /^\/practice\/attempts\/([^/]+)\/annotations\/([^/]+)\/(appeal|dispute)$/,
  );
  if (appealMatch && method === "POST") {
    const attemptId = decodeURIComponent(appealMatch[1] ?? "");
    const annotationKey = decodeURIComponent(appealMatch[2] ?? "");
    const kind: "APPEAL" | "DISPUTE" = appealMatch[3] === "appeal" ? "APPEAL" : "DISPUTE";
    if (kind === "DISPUTE") {
      await engine.setAnnotationDispute(attemptId, annotationKey);
    } else {
      await engine.setAnnotationAppeal(attemptId, annotationKey);
    }
    return {
      appealId: `local-appeal-${crypto.randomUUID()}`,
      status: kind === "DISPUTE" ? "ACCEPTED" : "PENDING",
      kind,
      annotationKey,
    } as T;
  }

  if (pathname === "/practice/wrong-questions" && method === "GET") {
    const { page, size } = localPagination(params);
    const includeDisputed = params.get("includeDisputed") === "true";
    const tag = params.get("tag");
    const bankId = params.get("bankId");
    // 引擎缓存的句子不携带 bankId，用「指定题库的句子集合」做归属过滤，等价于按题库筛选。
    const bankSentenceIds = bankId
      ? new Set((await engine.getCachedSentences(bankId)).map((sentence) => sentence.id))
      : null;
    const rows = (await engine.getWrongQuestions()).filter((row) => {
      if (bankSentenceIds && !bankSentenceIds.has(row.sentence.id)) {
        return false;
      }
      if (tag && !row.sentence.tags.includes(tag)) {
        return false;
      }
      // 与 mock 一致：整条错题的批注都被「不认同」时默认隐藏，显式 includeDisputed 才返回。
      if (
        !includeDisputed &&
        row.annotations.length > 0 &&
        row.annotations.every((a) => a.disputed)
      ) {
        return false;
      }
      return true;
    });
    const result = localPaginate(rows, page, size);
    return {
      ...result,
      items: result.items.map((row) => ({
        sentenceId: row.sentence.id,
        mode: row.mode,
        state: row.state,
        dueAt: row.dueAt,
        lastScore: row.score,
        lastAttemptId: row.attemptId,
        wrongCount: row.wrongCount,
        tags: row.sentence.tags,
        zh: row.sentence.zh,
        annotations: row.annotations,
      })),
    } as T;
  }

  if (pathname === "/practice/reviews" && method === "GET") {
    const { page, size } = localPagination(params);
    const state = params.get("state");
    const mode = params.get("mode");
    const dueOnly = params.get("due") === "true";
    const queue = await engine.getReviewQueue();
    let items = queue.rows.map((row) => engine.reviewToApi(row.review as StoredReview));
    if (state) {
      items = items.filter((item) => item.state === state);
    }
    if (mode) {
      items = items.filter((item) => item.mode === mode);
    }
    if (dueOnly) {
      const nowMs = Date.now();
      items = items.filter((item) => new Date(item.dueAt).getTime() <= nowMs);
    }
    return { ...localPaginate(items, page, size) } as T;
  }

  const resetReviewMatch = pathname.match(/^\/practice\/reviews\/([^/]+)\/reset$/);
  if (resetReviewMatch && method === "POST") {
    const card = await engine.resetReviewState(decodeURIComponent(resetReviewMatch[1] ?? ""));
    if (!card) {
      throw new ApiError(404, 40404, "复习记录不存在");
    }
    return engine.reviewToApi(card as StoredReview) as T;
  }

  if (pathname === "/practice/today" && method === "GET") {
    const snapshot = await engine.getTodaySnapshot();
    return {
      date: localDateString(new Date()),
      timezone: localTimezone(),
      dueCount: snapshot.dueCount,
      doneCount: snapshot.doneCount,
      goal: snapshot.goal,
      correctRate: snapshot.correctRate,
      streakDays: snapshot.streakDays,
    } as T;
  }

  if (pathname === "/analytics/accuracy" && method === "GET") {
    const snapshot = await engine.getStatsSnapshot();
    // 本地引擎按全量历史聚合（不按 from/to 切分）；区间参数仅回显，报表口径在页面上保持一致。
    return {
      from: params.get("from") ?? localDateString(new Date(Date.now() - 29 * 86_400_000)),
      to: params.get("to") ?? localDateString(new Date()),
      totalAttempts: snapshot.totalAttempts,
      correctRate: snapshot.correctRate,
      averageScore: snapshot.averageScore,
      byMode: snapshot.byMode,
    } as T;
  }

  if (pathname === "/analytics/error-tags" && method === "GET") {
    const snapshot = await engine.getStatsSnapshot();
    const total = snapshot.errorTags.reduce((sum, item) => sum + item.count, 0);
    return {
      from: params.get("from") ?? localDateString(new Date(Date.now() - 29 * 86_400_000)),
      to: params.get("to") ?? localDateString(new Date()),
      items: snapshot.errorTags.map((item) => ({
        tag: item.tag,
        count: item.count,
        ratio: total === 0 ? 0 : Math.round((item.count / total) * 1000) / 10,
      })),
    } as T;
  }

  throw new ApiError(404, 40400, "本地模式暂不支持该接口");
}

/**
 * 业务 API 的统一入口；各领域客户端用它发起请求，mock 开关与 CSRF/刷新逻辑集中在这里。
 * @param path `/api` 之后的路径
 * @param init fetch 初始化参数
 * @param options 重试与超时控制项
 * @returns 解包后的 data
 * @throws ApiError 网络、超时、HTTP 或业务码失败
 */
export function apiRequest<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  return request<T>(path, init, options);
}

/** 认证相关 API 的薄封装；除冷启动刷新外，请求都经 request 统一处理超时、刷新与错误。 */
export const api = {
  /**
   * 登录并把 access token 存入内存；refresh token 由响应 Set-Cookie 写入 HttpOnly Cookie。
   * @param credentials 用户名与密码
   * @returns access token、有效期与当前用户
   * @throws ApiError 网络失败、凭据错误、限流或响应结构异常
   */
  async login(credentials: { username: string; password: string }): Promise<AuthResponse> {
    const data = await request<unknown>(
      "/auth/login",
      { method: "POST", body: JSON.stringify(credentials) },
      { skipRefresh: true, skipUnauthorizedRedirect: true },
    );
    const parsed = authResponseSchema.safeParse(data);
    if (!parsed.success) {
      throw new ApiError(0, FALLBACK_ERROR_CODE, "服务响应异常，请稍后重试");
    }
    accessToken = parsed.data.accessToken;
    // 登录后旧 CSRF 可能已失效，强制下一次请求重新读取服务端 token。
    cachedCsrfToken = null;
    return parsed.data;
  },

  /**
   * 获取当前用户；冷启动没有 access token 时先尝试用 refresh cookie 恢复会话。
   * mock 模式没有 HttpOnly refresh cookie，直接请求 /auth/me 由 mock 会话判断。
   * @param signal TanStack Query 的取消信号，登出/切页时中止请求
   * @returns 当前用户
   * @throws ApiError 网络失败、未登录或响应结构异常
   */
  async me(signal?: AbortSignal): Promise<CurrentUser> {
    if (!accessToken && isMockEnabled()) {
      const data = await request<unknown>("/auth/me", { signal });
      return parseUser(data);
    }
    if (!accessToken) {
      const outcome = await refreshSession();
      if (outcome.kind === "ok") {
        return outcome.auth.user;
      }
      if (outcome.kind === "anonymous") {
        throw new ApiError(401, 40100, "请先登录");
      }
      throw outcome.error;
    }
    const data = await request<unknown>("/auth/me", { signal });
    return parseUser(data);
  },

  /**
   * 吊销 refresh 会话并清空内存 access token；请求失败时保留 token 以便重试。
   * @returns 无返回值
   * @throws ApiError 网络失败或服务端未能吊销会话
   */
  async logout(): Promise<void> {
    await request<void>(
      "/auth/logout",
      { method: "POST" },
      { skipRefresh: true, skipUnauthorizedRedirect: true },
    );
    accessToken = null;
  },

  /**
   * 邀请码注册；成功后后端直接返回登录态。
   * @param input 邮箱、可选用户名、密码与邀请码
   * @returns 登录响应
   * @throws ApiError 邀请码失效、重复账号或参数校验失败
   */
  async register(input: {
    email: string;
    username?: string;
    password: string;
    inviteCode: string;
  }): Promise<AuthResponse> {
    const data = await request<unknown>(
      "/auth/register",
      { method: "POST", body: JSON.stringify(input) },
      { skipRefresh: true, skipUnauthorizedRedirect: true },
    );
    const parsed = authResponseSchema.safeParse(data);
    if (!parsed.success) {
      throw new ApiError(0, FALLBACK_ERROR_CODE, "服务响应异常，请稍后重试");
    }
    accessToken = parsed.data.accessToken;
    cachedCsrfToken = null;
    return parsed.data;
  },
};
