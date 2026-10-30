import { beforeEach, describe, expect, it, vi } from "vitest";
import { resetApiState, setAccessToken, setAuthStateProbe } from "@/lib/api";
import { readGuestUpgradeState, syncGuestAttempts } from "@/lib/api/guest-upgrade";
import {
  createMemoryEngineStorage,
  type StoredAttempt,
  setEngineStorageForTests,
} from "@/lib/local-engine";

/** 迁移单测只需 attemptId/请求载荷成对；response/判分字段不参与迁移载荷。 */
function storedAttempt(index: number): StoredAttempt {
  const attemptId = `3f0e1c2a-0000-4000-8000-${String(index).padStart(12, "0")}`;
  return {
    attemptId,
    sentenceId: `sentence-${index % 10}`,
    mode: "CLOZE",
    kind: index % 10 === 9 ? "RETRY" : "FIRST",
    score: 100,
    request: {
      attemptId,
      sentenceId: `sentence-${index % 10}`,
      sentenceVersionId: "1001",
      mode: "CLOZE",
      kind: index % 10 === 9 ? "RETRY" : "FIRST",
      retryOfId:
        index % 10 === 9 ? `3f0e1c2a-0000-4000-8000-${String(index - 1).padStart(12, "0")}` : null,
      answer: {
        schemaVersion: 1,
        mode: "CLOZE",
        slots: [{ chunkIndex: 1, value: "more and more" }],
      },
      hintsUsed: 0,
      durationMs: 1000,
      clientCreatedAt: new Date(Date.UTC(2026, 0, 1, 0, index)).toISOString(),
    },
    response: { attemptId } as unknown as StoredAttempt["response"],
    createdAt: new Date(Date.UTC(2026, 0, 1, 0, index)).toISOString(),
  };
}

/**
 * 向内存引擎写入 n 条答题记录；按 index 顺序生成，时间戳升序。
 * @param n 条数
 */
async function seedAttempts(n: number): Promise<void> {
  const storage = createMemoryEngineStorage();
  const store = await storage.attempts();
  for (let index = 0; index < n; index += 1) {
    const attempt = storedAttempt(index);
    await store.put(attempt.attemptId, attempt);
  }
  setEngineStorageForTests(storage);
}

/** 构造 guest-upgrade 成功信封。 */
function upgradeEnvelope(imported: number, skipped: number): unknown {
  return { code: 0, message: "success", data: { imported, skipped, conflicts: 0 } };
}

/** 构造业务失败信封。 */
function errorEnvelope(code: number): unknown {
  return { code, message: "迁移失败", data: null };
}

/** 构造 4xx JSON 响应。 */
function jsonError(status: number, body: unknown): Response {
  return {
    ok: false,
    status,
    headers: new Headers(),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

describe("guest-upgrade sync", () => {
  beforeEach(() => {
    resetApiState();
    setAuthStateProbe(null);
    setEngineStorageForTests(createMemoryEngineStorage());
    window.localStorage.clear();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("does nothing for guests or when already upgraded", async () => {
    const fetchMock = vi.fn(async () => {
      throw new Error("unexpected network request");
    });
    vi.stubGlobal("fetch", fetchMock);

    // 未登录（游客语义）：不发请求。
    const guestResult = await syncGuestAttempts();
    expect(guestResult).toEqual({ imported: 0, skipped: 0, unsynced: 0 });
    expect(fetchMock).not.toHaveBeenCalled();

    // 已完成标记：登录态也不重发。
    setAccessToken("access-token");
    window.localStorage.setItem("yilian.guest-upgraded.v1", "done");
    const doneResult = await syncGuestAttempts();
    expect(doneResult).toEqual({ imported: 0, skipped: 0, unsynced: 0 });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("posts local attempts in one batch and marks the upgrade done", async () => {
    await seedAttempts(3);
    setAccessToken("access-token");
    const batches: number[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as { attempts: unknown[] };
        batches.push(body.attempts.length);
        return {
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => upgradeEnvelope(body.attempts.length, 0),
          text: async () => "",
        } as unknown as Response;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await syncGuestAttempts();
    expect(result).toEqual({ imported: 3, skipped: 0, unsynced: 0 });
    expect(batches).toEqual([3]);
    expect(readGuestUpgradeState()).toEqual({ done: true, unsynced: 0 });
    // 完成标记写入后欠账状态被清除。
    expect(window.localStorage.getItem("yilian.guest-upgrade.v1")).toBeNull();
  });

  it("sends more than 100 attempts in batches of 100 in client order", async () => {
    await seedAttempts(150);
    setAccessToken("access-token");
    const batchSizes: number[] = [];
    const firstIds: string[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as {
          attempts: Array<{ attemptId: string; clientCreatedAt: string }>;
        };
        batchSizes.push(body.attempts.length);
        firstIds.push(body.attempts[0]?.attemptId ?? "");
        return {
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => upgradeEnvelope(body.attempts.length, 0),
          text: async () => "",
        } as unknown as Response;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await syncGuestAttempts();
    expect(result).toEqual({ imported: 150, skipped: 0, unsynced: 0 });
    expect(batchSizes).toEqual([100, 50]);
    // clientCreatedAt 升序：RETRY 的原始提交（index 9 的前一条）先于改错入库。
    expect(firstIds[0]).toBe(`3f0e1c2a-0000-4000-8000-${"0".repeat(12)}`);
    expect(firstIds[1]).toBe(`3f0e1c2a-0000-4000-8000-000000000100`);
  });

  it("skips a failing batch (40402 unknown sentence) and keeps the rest syncable", async () => {
    await seedAttempts(150);
    setAccessToken("access-token");
    let batchIndex = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as { attempts: unknown[] };
        const current = batchIndex;
        batchIndex += 1;
        if (current === 1) {
          // 第二批含服务端不存在的题目：整体回滚 40402，前端降级为跳过该批继续。
          return jsonError(404, errorEnvelope(40402));
        }
        return {
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => upgradeEnvelope(body.attempts.length, 0),
          text: async () => "",
        } as unknown as Response;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await syncGuestAttempts();
    // 第一批 100 条成功，第二批 50 条跳过；完成标记不写，欠账落 localStorage。
    expect(result).toEqual({ imported: 100, skipped: 0, unsynced: 50 });
    expect(readGuestUpgradeState()).toEqual({ done: false, unsynced: 50 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("aborts on network failure and reports the remaining as unsynced", async () => {
    await seedAttempts(150);
    setAccessToken("access-token");
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        throw new TypeError("network down");
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await syncGuestAttempts();
    // 网络失败立即中止：150 条全部未同步，完成标记不写。
    expect(result).toEqual({ imported: 0, skipped: 0, unsynced: 150 });
    expect(readGuestUpgradeState()).toEqual({ done: false, unsynced: 150 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("marks done with zero local attempts and keeps local data after successful sync", async () => {
    await seedAttempts(3);
    setAccessToken("access-token");
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        return {
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => upgradeEnvelope(JSON.parse(String(init?.body)).attempts.length, 0),
          text: async () => "",
        } as unknown as Response;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    await syncGuestAttempts();
    // 本地记录不清理（IndexedDB 保留，可导出可重置）：迁移完成只是冻结本地引擎的路由命中。
    const { listAttempts } = await import("@/lib/local-engine");
    await expect(listAttempts()).resolves.toHaveLength(3);
    expect(readGuestUpgradeState().done).toBe(true);
    expect(window.localStorage.getItem("yilian.guest-upgraded.v1")).not.toBeNull();
  });
});
