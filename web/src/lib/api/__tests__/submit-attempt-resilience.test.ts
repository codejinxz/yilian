import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  enqueueAttempt,
  flushQueuedAttempts,
  readQueuedAttempts,
} from "@/features/practice/offline-queue";
import { resetApiState, setAccessToken } from "@/lib/api";
import { submitAttempt } from "@/lib/api/practice";
import { ApiError, isTransportError } from "@/lib/api-error";
import type { AttemptRequest } from "@/lib/schemas/practice";

const STORAGE_KEY = "yilian.offline-attempts.v1";

/** 构造合法的提交载荷；attemptId 即服务端幂等键，重试与重放都靠它去重。 */
function attemptRequest(): AttemptRequest {
  return {
    attemptId: "3f0e1c2a-0000-4000-8000-000000000001",
    sentenceId: "sentence-1",
    sentenceVersionId: "1001",
    mode: "CLOZE",
    kind: "FIRST",
    retryOfId: null,
    answer: { schemaVersion: 1, mode: "CLOZE", slots: [{ chunkIndex: 1, value: "more and more" }] },
    hintsUsed: 0,
    durationMs: 1000,
    clientCreatedAt: "2026-11-03T00:00:00.000Z",
  };
}

/** 判分响应；与 attemptResponseSchema 同形，重试命中服务端幂等时 replayed=true。 */
function attemptResponse(attemptId: string, replayed = false) {
  return {
    attemptId,
    sentenceId: "sentence-1",
    sentenceVersionId: "1001",
    mode: "CLOZE",
    kind: "FIRST",
    score: 60,
    maxScore: 100,
    result: {
      schemaVersion: 1,
      score: 60,
      maxScore: 100,
      hitCount: 1,
      totalCount: 2,
      annotations: [],
      summary: "有两处时态问题",
    },
    grading: {
      id: "9001",
      sequence: 1,
      grader: "LOCAL",
      normalizerVersion: "norm-1",
      rubricVersion: "rubric-1",
      createdAt: "2026-11-03T00:00:00.000Z",
    },
    review: reviewCard(replayed),
    reference: { ref: "It is becoming more and more popular.", note: null },
    nextAction: { retryRequired: true, canReveal: true },
    replayed,
  };
}

/**
 * 服务端排程后的内嵌复习卡：严格按 API §6.2 的字段集——只有排程投影，
 * 不带 sentenceId/mode（前端曾要求必填导致 200 响应误判失败）。
 */
function serverReviewCard(): Record<string, unknown> {
  return {
    id: "10",
    state: "LEARNING",
    fsrsState: "LEARNING",
    dueAt: "2026-11-04T00:00:00Z",
    stability: 2.4,
    difficulty: 5.3,
    reps: 1,
    lapses: 1,
    scheduledDays: 1,
    rating: 2,
  };
}

/** 本地引擎的离线复习卡：在服务端字段之上补 sentenceId/mode（完整队列卡形状）。 */
function localReviewCard(): Record<string, unknown> {
  return {
    ...serverReviewCard(),
    sentenceId: "sentence-1",
    mode: "CLOZE",
    lastRating: 2,
    lastReviewedAt: "2026-11-03T00:00:00Z",
  };
}

function reviewCard(replayed: boolean): Record<string, unknown> | null {
  // 幂等重放用例不带卡（review=null），其余用例给服务端形状的卡。
  return replayed ? null : serverReviewCard();
}

/** 统一响应信封。 */
function successEnvelope(data: unknown): Response {
  return new Response(JSON.stringify({ code: 0, message: "success", data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

/** 提交端点的 CSRF 前置：请求客户端从可读 cookie 取 token。 */
function seedCsrfCookie(): void {
  // biome-ignore lint/suspicious/noDocumentCookie: 测试需要预置可读的 CSRF cookie
  document.cookie = "XSRF-TOKEN=test-token; path=/";
}

describe("submitAttempt 的传输失败防御（014 冒烟离线误判根因）", () => {
  beforeEach(() => {
    resetApiState();
    // 持有 access token 让 /practice/* 走网络而不是游客本地引擎。
    setAccessToken("access-token");
    seedCsrfCookie();
    localStorage.removeItem(STORAGE_KEY);
  });

  afterEach(() => {
    // 用过 fake timers 的用例必须还原，否则 800ms 重试退避会冻结后续用例。
    vi.useRealTimers();
  });

  it("015 AC-4: 超时(transport)后自动用同一载荷重试一次并成功——服务端曾处理也不产生假离线", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/practice/attempts")) {
        if (fetchMock.mock.calls.length === 1) {
          // 首次模拟 10 秒超时被客户端中止：请求可能已被服务端处理。
          throw new DOMException("请求超时", "TimeoutError");
        }
        return successEnvelope(attemptResponse(attemptRequest().attemptId, true));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const request = attemptRequest();
    const pending = submitAttempt(request);
    // 首次失败后的 800ms 重试退避 + 后续微任务。
    await vi.advanceTimersByTimeAsync(1000);
    const response = await pending;

    expect(fetchMock).toHaveBeenCalledTimes(2);
    // 重试是同一载荷、同一幂等键；服务端 replayed=true 也原样接收，页面走成功分支。
    expect(response.attemptId).toBe(request.attemptId);
    expect(response.replayed).toBe(true);
    expect(readQueuedAttempts()).toHaveLength(0);
  });

  it("015 AC-4: 2xx 后 body 校验失败(response 类)只调用一次、不判离线——服务端必已成功", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/practice/attempts")) {
        return successEnvelope({ attemptId: attemptRequest().attemptId });
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const failure = await submitAttempt(attemptRequest()).catch((error: unknown) => error);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(failure).toBeInstanceOf(ApiError);
    const apiError = failure as ApiError;
    expect(apiError.status).toBe(0);
    expect(apiError.networkKind).toBe("response");
    expect(isTransportError(failure)).toBe(false);
  });

  it("015 AC-4: 浏览器报告离线时传输失败不重试，直接交给页面入离线队列", async () => {
    const onlineSpy = vi.spyOn(window.navigator, "onLine", "get").mockReturnValue(false);
    const fetchMock = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    });
    vi.stubGlobal("fetch", fetchMock);

    const failure = await submitAttempt(attemptRequest()).catch((error: unknown) => error);

    expect(onlineSpy).toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(isTransportError(failure)).toBe(true);
  });

  it("015 AC-4: 在线但连续传输失败时重试一次后抛 transport 错误，页面据此入队而不是谎报成功", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith("/api/practice/attempts")) {
        throw new TypeError("Failed to fetch");
      }
      throw new Error(`unexpected request: ${String(input)}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    // catch 先挂上再推进时钟：避免 rejection 在断言前落成 unhandled rejection。
    const failurePromise = submitAttempt(attemptRequest()).catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(1000);
    const failure = await failurePromise;

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(isTransportError(failure)).toBe(true);
  });
});

describe("提交响应内嵌复习卡的形状兼容（020 AC-0 提交假失败根因）", () => {
  beforeEach(() => {
    resetApiState();
    setAccessToken("access-token");
    seedCsrfCookie();
    localStorage.removeItem(STORAGE_KEY);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("[AC-0] 服务端 review 无 sentenceId/mode（API §6.2 排程投影）时提交照常成功", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith("/api/practice/attempts")) {
        return successEnvelope(attemptResponse(attemptRequest().attemptId));
      }
      throw new Error(`unexpected request: ${String(input)}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const response = await submitAttempt(attemptRequest());

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(response.review?.state).toBe("LEARNING");
    expect("sentenceId" in (response.review ?? {})).toBe(false);
  });

  it("[AC-0] 本地引擎 review 补 sentenceId/mode（完整队列卡）时同样可解析", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith("/api/practice/attempts")) {
        return successEnvelope({
          ...attemptResponse(attemptRequest().attemptId),
          review: localReviewCard(),
        });
      }
      throw new Error(`unexpected request: ${String(input)}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const response = await submitAttempt(attemptRequest());

    expect(response.review?.sentenceId).toBe("sentence-1");
    expect(response.review?.mode).toBe("CLOZE");
    expect(response.review?.lastReviewedAt).toBe("2026-11-03T00:00:00Z");
  });
});

describe("离线队列补交的分类（014 冒烟同载荷重放链路）", () => {
  beforeEach(() => {
    resetApiState();
    setAccessToken("access-token");
    seedCsrfCookie();
    localStorage.removeItem(STORAGE_KEY);
  });

  it("015 AC-4: 补交成功后队列清空对应项", async () => {
    const request = attemptRequest();
    enqueueAttempt(request);
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).endsWith("/api/practice/attempts")) {
          return successEnvelope(attemptResponse(request.attemptId));
        }
        throw new Error(`unexpected request: ${String(input)}`);
      }),
    );

    const result = await flushQueuedAttempts();

    expect(result.synced).toBe(1);
    expect(result.message).toBeNull();
    expect(readQueuedAttempts()).toHaveLength(0);
  });

  it("015 AC-4: 补交遇 transport 失败保留队列并提示仍然离线，绝不静默丢弃", async () => {
    enqueueAttempt(attemptRequest());
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );

    const result = await flushQueuedAttempts();

    expect(result.synced).toBe(0);
    expect(result.message).toBe("仍然离线，待同步答卷已保留在本机。");
    expect(readQueuedAttempts()).toHaveLength(1);
  });
});
