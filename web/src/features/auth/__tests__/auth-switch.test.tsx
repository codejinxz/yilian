import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "@/app/providers";
import { createAppRouter } from "@/app/router";
import {
  ApiError,
  getAccessToken,
  isGuestSession,
  resetApiState,
  setAccessToken,
  setAuthStateProbe,
} from "@/lib/api";
import { getToday, submitAttempt } from "@/lib/api/practice";
import {
  createMemoryEngineStorage,
  savePlacementState,
  setEngineStorageForTests,
} from "@/lib/local-engine";
import { createQueryClient } from "@/lib/query";
import type { PracticeSentence } from "@/lib/schemas/practice";

const ADMIN = { id: 1, username: "admin", role: "ADMIN" } as const;

/** 今日启动台查询的合法夹具；streakDays=5 用于断言数据确实来自服务端而非本地引擎的全 0。 */
const TODAY = {
  date: "2026-01-05",
  timezone: "Asia/Shanghai",
  dueCount: 2,
  doneCount: 1,
  goal: 3,
  correctRate: 0,
  streakDays: 5,
} as const;

/** 壳层 AppShell 挂载后会拉取用户设置；字段须满足 settingsSchema。 */
const SETTINGS = {
  timezone: "Asia/Shanghai",
  dailyGoal: 3,
  ratingStandard: "TEM8",
  desiredRetention: 0.9,
  theme: "paper",
  notificationsEnabled: false,
} as const;

/** 本地题库缓存里的最小可练句子：一个挖空、答案唯一，够 submitLocalAttempt 判分。 */
const SENTENCE = {
  id: "sentence-1",
  version: 12,
  sentenceVersionId: "1001",
  questionVersion: 3,
  status: "PUBLISHED",
  deleted: false,
  zh: "他越来越重视这件事。",
  ref: "He is paying more and more attention to this.",
  note: null,
  chunks: [{ index: 1, zh: "more and more", answers: ["more and more"], tag: null, note: null }],
  blank: [1],
  tags: ["副词结构"],
  level: null,
} as const;

/** 提交载荷的最小合法 attempt 请求；attemptId 必须是 UUID（attemptRequestSchema 校验）。 */
const ATTEMPT_REQUEST = {
  attemptId: "3f0e1c2a-0000-4000-8000-000000000001",
  sentenceId: "sentence-1",
  sentenceVersionId: "1001",
  mode: "CLOZE" as const,
  kind: "FIRST" as const,
  retryOfId: null,
  answer: {
    schemaVersion: 1 as const,
    mode: "CLOZE" as const,
    slots: [{ chunkIndex: 1, value: "more and more" }],
  },
  hintsUsed: 0,
  durationMs: 1000,
};

/**
 * 壳层路由的定级门要求本地已有「已定级」状态；预置一份，让用例能直达今日等页面。
 */
function markPlaced(): void {
  savePlacementState({ placed: true, startLevel: "中级·日常", missedTags: [] });
}

/**
 * 构造一个带 headers/json/text 的最小 Response。
 * @param body 响应体
 * @param status HTTP 状态
 * @returns 测试用 Response
 */
function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

/**
 * 构造后端成功信封。
 * @param data 业务数据
 * @returns code 为 0 的统一响应
 */
function successEnvelope(data: unknown): { code: number; message: string; data: unknown } {
  return { code: 0, message: "success", data };
}

/**
 * 构造登录或刷新成功响应。
 * @returns 带认证数据的成功信封
 */
function authEnvelope(): { code: number; message: string; data: unknown } {
  return successEnvelope({
    accessToken: "access-token",
    tokenType: "Bearer",
    expiresIn: 900,
    user: ADMIN,
  });
}

/**
 * 向内存引擎预置一个题库缓存，让本地取题/判分可以完整跑通。
 * @returns 已注入的内存存储
 */
async function seedLocalBank(): Promise<void> {
  const storage = createMemoryEngineStorage();
  await storage.bankCache().then((store) =>
    store.put("bank-1", {
      bankId: "bank-1",
      sentences: [SENTENCE as unknown as PracticeSentence],
      fetchedAt: new Date().toISOString(),
    }),
  );
  setEngineStorageForTests(storage);
}

/**
 * 以内存 history 渲染整个应用。
 * @param initialPath 初始路由路径
 * @returns 本次渲染使用的 router 与 queryClient
 */
function renderApp(initialPath: string) {
  const queryClient = createQueryClient();
  const router = createAppRouter({
    queryClient,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
  });
  render(<AppProviders router={router} queryClient={queryClient} />);
  return { router, queryClient };
}

/**
 * 从 fetch mock 的调用里筛出命中本地路由组的 URL（practice/analytics）。
 * @param fetchMock fetch stub
 * @returns 命中本地路由组的请求 URL 列表
 */
function localRouteCalls(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return fetchMock.mock.calls
    .map((call) => String(call[0]))
    .filter((url) => url.includes("/api/practice") || url.includes("/api/analytics"));
}

describe("auth-state routing switch", () => {
  beforeEach(() => {
    resetApiState();
    // probe 是模块级状态，逐用例清空；组件级用例由 renderApp 重新注入。
    setAuthStateProbe(null);
    setEngineStorageForTests(createMemoryEngineStorage());
    window.localStorage.clear();
    // jsdom 没有 IndexedDB；生产语义下本地路由需要它可用，stub 一个标记对象
    // 让 isLocalRouteEnabled 走真实判定（引擎存储已注入内存实现）。
    vi.stubGlobal("indexedDB", {});
    // 冷启动 refresh 是 cookie 认证；给可读 CSRF cookie 让 refresh 不必再取 token。
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("answers practice reads locally for guests with zero network calls", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("unexpected network request");
      }),
    );
    const today = await getToday();
    expect(today.dueCount).toBe(0);
    expect(today.streakDays).toBe(0);
    // 游客红线：practice 读请求被本地路由拦截，绝不触网。
    expect(vi.mocked(fetch).mock.calls).toHaveLength(0);
  });

  it("answers attempt submissions locally for guests with zero network writes", async () => {
    await seedLocalBank();
    const fetchMock = vi.fn(async () => {
      throw new Error("unexpected network request");
    });
    vi.stubGlobal("fetch", fetchMock);
    const response = await submitAttempt(ATTEMPT_REQUEST);
    expect(response.attemptId).toBe(ATTEMPT_REQUEST.attemptId);
    expect(response.replayed).toBe(false);
    expect(localRouteCalls(fetchMock)).toHaveLength(0);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends the same requests to the server once signed in", async () => {
    setAccessToken("access-token");
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/practice/today")) {
        return jsonResponse(successEnvelope(TODAY));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    const today = await getToday();
    expect(today.streakDays).toBe(5);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain("/api/practice/today");
  });

  it("routes attempt submissions to the server when signed in", async () => {
    await seedLocalBank();
    setAccessToken("access-token");
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/practice/attempts")) {
        expect(init?.method).toBe("POST");
        const headers = init?.headers as Headers | undefined;
        expect(headers?.get("X-XSRF-TOKEN")).toBe("test-token");
        return jsonResponse(
          successEnvelope({
            attemptId: ATTEMPT_REQUEST.attemptId,
            sentenceId: ATTEMPT_REQUEST.sentenceId,
            sentenceVersionId: "1001",
            mode: "CLOZE",
            kind: "FIRST",
            score: 100,
            maxScore: 100,
            result: {
              schemaVersion: 1,
              score: 100,
              maxScore: 100,
              hitCount: 1,
              totalCount: 1,
              annotations: [],
              summary: "全对",
            },
            grading: {
              id: "1",
              sequence: 1,
              grader: "LOCAL",
              normalizerVersion: "norm-1",
              rubricVersion: "rubric-1",
              createdAt: "2026-01-05T00:00:00Z",
            },
            review: null,
            reference: { ref: SENTENCE.ref, note: null },
            nextAction: { retryRequired: false, canReveal: true },
            replayed: false,
          }),
        );
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    const response = await submitAttempt(ATTEMPT_REQUEST);
    expect(response.replayed).toBe(false);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain("/api/practice/attempts");
  });

  it("flips local answering to the network as soon as the probe confirms sign-in", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("unexpected network request");
      }),
    );
    // 探针未就绪：按游客处理。
    expect(isGuestSession()).toBe(true);
    const guestToday = await getToday();
    expect(guestToday.streakDays).toBe(0);
    expect(vi.mocked(fetch).mock.calls).toHaveLength(0);

    // 探针确认登录：同一路径立即放行到服务端（fetch stub 抛错，包装为网络 ApiError）。
    setAuthStateProbe(() => true);
    expect(isGuestSession()).toBe(false);
    await expect(getToday()).rejects.toBeInstanceOf(ApiError);
    expect(vi.mocked(fetch).mock.calls).toHaveLength(1);

    // 探针清除：回到游客语义。
    setAuthStateProbe(null);
    expect(isGuestSession()).toBe(true);
  });

  it("keeps the guest flow fully local on /today with zero practice or analytics calls", async () => {
    markPlaced();
    const fetchMock = vi.fn(async () => {
      throw new Error("unexpected network request");
    });
    vi.stubGlobal("fetch", fetchMock);

    renderApp("/today");

    // 今日页进入成功态（本地引擎应答的全 0 数字），证明游客可完整使用页面。
    expect(await screen.findByText(/句到期/)).toBeInTheDocument();
    const localCalls = localRouteCalls(fetchMock);
    expect(localCalls).toHaveLength(0);
    expect(getAccessToken()).toBeNull();
  });

  it("serves /today from the server for a signed-in user", async () => {
    markPlaced();
    setAccessToken("access-token");
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/practice/today")) {
        return jsonResponse(successEnvelope(TODAY));
      }
      if (url.endsWith("/api/me/settings")) {
        return jsonResponse(successEnvelope(SETTINGS));
      }
      if (url.endsWith("/api/auth/me")) {
        return jsonResponse(successEnvelope(ADMIN));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderApp("/today");

    // streakDays=5 只可能来自服务端夹具：登录用户同页面请求必须出现在服务端。
    expect(await screen.findByText(/连续 5 天/)).toBeInTheDocument();
    expect(localRouteCalls(fetchMock)).not.toHaveLength(0);
    expect(getAccessToken()).toBe("access-token");
  });

  it("switches a restored session from local answers to server data via query invalidation", async () => {
    markPlaced();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(authEnvelope());
      }
      if (url.endsWith("/api/practice/today")) {
        return jsonResponse(successEnvelope(TODAY));
      }
      if (url.endsWith("/api/me/settings")) {
        return jsonResponse(successEnvelope(SETTINGS));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderApp("/today");

    // 冷启动竞态：探针确认前首屏按游客本地应答（最坏情况），me 确认登录后
    // 失效 practice Query 重取，最终数据必须来自服务端（streakDays=5）。
    await waitFor(async () => {
      expect(screen.getByText(/连续 5 天/)).toBeInTheDocument();
    });
    expect(getAccessToken()).toBe("access-token");
    expect(localRouteCalls(fetchMock)).not.toHaveLength(0);
  });

  it("serves the stats page from analytics endpoints when signed in", async () => {
    markPlaced();
    setAccessToken("access-token");
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/api/analytics/accuracy")) {
        return jsonResponse(
          successEnvelope({
            from: "2026-01-01",
            to: "2026-01-05",
            totalAttempts: 42,
            correctRate: 72.5,
            averageScore: 81.3,
            byMode: [{ mode: "CLOZE", attempts: 42, correctRate: 72.5 }],
            daily14: Array.from({ length: 14 }, (_, index) => ({
              date: `2026-01-${String(index + 1).padStart(2, "0")}`,
              attempts: index,
              correctRate: null,
            })),
            recent: [
              {
                attemptId: "attempt-1",
                sentenceId: SENTENCE.id,
                mode: "CLOZE",
                kind: "FIRST",
                score: 80,
                createdAt: "2026-01-05T00:00:00Z",
              },
            ],
          }),
        );
      }
      if (url.includes("/api/analytics/error-tags")) {
        return jsonResponse(
          successEnvelope({
            from: "2026-01-01",
            to: "2026-01-05",
            items: [{ tag: "搭配", count: 3, ratio: 60 }],
          }),
        );
      }
      if (url.endsWith("/api/practice/today")) {
        return jsonResponse(successEnvelope(TODAY));
      }
      if (url.endsWith("/api/me/settings")) {
        return jsonResponse(successEnvelope(SETTINGS));
      }
      if (url.endsWith("/api/auth/me")) {
        return jsonResponse(successEnvelope(ADMIN));
      }
      // recent 的中文句 join 会拉一次公开题库；空列表让引擎缓存保持为空即可。
      if (url.includes("/api/banks")) {
        return jsonResponse(
          successEnvelope({
            items: [],
            page: 1,
            size: 100,
            total: 0,
            totalPages: 1,
            hasNext: false,
          }),
        );
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderApp("/stats");

    // 累计 42 句只可能来自服务端 accuracy 契约；游客分支的引擎快照在空引擎下恒为 0。
    expect(await screen.findByText("42")).toBeInTheDocument();
    expect(screen.getByText("搭配")).toBeInTheDocument();
    expect(localRouteCalls(fetchMock)).not.toHaveLength(0);
  });

  it("serves the review page from the reviews endpoint when signed in", async () => {
    markPlaced();
    setAccessToken("access-token");
    const reviewCard = {
      id: "10",
      sentenceId: SENTENCE.id,
      mode: "CLOZE",
      state: "LEARNING",
      fsrsState: "LEARNING",
      dueAt: "2026-01-05T00:00:00Z",
      stability: 2.4,
      difficulty: 5.3,
      reps: 1,
      lapses: 1,
      scheduledDays: 1,
      lastRating: 2,
      lastReviewedAt: "2026-01-04T00:00:00Z",
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/api/practice/reviews")) {
        // 汇总用 size=1 的 total 拼三档数字；列表用同一响应返回一页。
        const size = Number(new URL(url).searchParams.get("size") ?? 20);
        return jsonResponse(
          successEnvelope({
            items: size === 1 ? [] : [reviewCard],
            page: 1,
            size,
            total: 1,
            totalPages: 1,
            hasNext: false,
          }),
        );
      }
      if (url.endsWith("/api/practice/reviews/overload")) {
        return jsonResponse(
          successEnvelope({ dueCount: 3, threshold: 20, pushLimit: 5, overload: false }),
        );
      }
      if (url.endsWith("/api/me/settings")) {
        return jsonResponse(successEnvelope(SETTINGS));
      }
      if (url.endsWith("/api/auth/me")) {
        return jsonResponse(successEnvelope(ADMIN));
      }
      if (url.includes("/api/banks?")) {
        return jsonResponse(
          successEnvelope({
            items: [],
            page: 1,
            size: 100,
            total: 0,
            totalPages: 1,
            hasNext: false,
          }),
        );
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderApp("/review");

    // 复习行渲染（join 不到中文句时按「题目 {短id}」占位），三档汇总来自契约 total
    //（due/learning/mastered 三个 size=1 查询的 total 都是 1，页面多处出现该数字）。
    expect(await screen.findByText(/题目/)).toBeInTheDocument();
    expect(screen.getAllByText("1").length).toBeGreaterThan(0);
    expect(localRouteCalls(fetchMock)).not.toHaveLength(0);
  });
});
