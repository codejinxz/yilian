import { beforeEach, describe, expect, it, vi } from "vitest";
import { resetApiState, setAccessToken, setAuthStateProbe } from "@/lib/api";
import { readGuestUpgradeState, syncGuestAttempts } from "@/lib/api/guest-upgrade";
import {
  createMemoryEngineStorage,
  type EngineStorage,
  type StoredAttempt,
  setEngineStorageForTests,
} from "@/lib/local-engine";

/** 与 storedAttempt 同规则的 attemptId 生成，供断言复用。 */
function attemptIdAt(index: number): string {
  return `3f0e1c2a-0000-4000-8000-${String(index).padStart(12, "0")}`;
}

/** 迁移单测只需 attemptId/请求载荷成对；response/判分字段不参与迁移载荷。 */
function storedAttempt(index: number): StoredAttempt {
  const attemptId = attemptIdAt(index);
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
      retryOfId: index % 10 === 9 ? attemptIdAt(index - 1) : null,
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

/** 当前用例注入的内存存储；appendAttempts 往同一份后端追加（模拟标记之后又练的新记录）。 */
let currentStorage: EngineStorage | null = null;

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
  currentStorage = storage;
  setEngineStorageForTests(storage);
}

/**
 * 向已 seed 的内存引擎追加记录（模拟完成标记之后登出又练的部分）。
 * @param from 起始 index（含）
 * @param count 条数
 */
async function appendAttempts(from: number, count: number): Promise<void> {
  if (!currentStorage) {
    throw new Error("seedAttempts must run before appendAttempts");
  }
  const store = await currentStorage.attempts();
  for (let index = from; index < from + count; index += 1) {
    const attempt = storedAttempt(index);
    await store.put(attempt.attemptId, attempt);
  }
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

/** guest-upgrade 成功应答的 Response 形状。 */
function upgradeOk(body: { attempts: Array<{ attemptId: string }> }): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers(),
    json: async () => upgradeEnvelope(body.attempts.length, 0),
    text: async () => "",
  } as unknown as Response;
}

describe("guest-upgrade sync", () => {
  beforeEach(() => {
    resetApiState();
    setAuthStateProbe(null);
    setEngineStorageForTests(createMemoryEngineStorage());
    currentStorage = null;
    window.localStorage.clear();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("does nothing for guests and does not resend when every local attempt is already migrated", async () => {
    const fetchMock = vi.fn(async () => {
      throw new Error("unexpected network request");
    });
    vi.stubGlobal("fetch", fetchMock);

    // 未登录（游客语义）：不发请求。
    const guestResult = await syncGuestAttempts();
    expect(guestResult).toEqual({ imported: 0, skipped: 0, unsynced: 0 });
    expect(fetchMock).not.toHaveBeenCalled();

    // 全部本机记录都在增量标记内：登录态也不重发（换「任何请求都失败」的桩验证）。
    await seedAttempts(3);
    setAccessToken("access-token");
    const firstSyncMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as { attempts: Array<{ attemptId: string }> };
        return upgradeOk(body);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", firstSyncMock);
    const first = await syncGuestAttempts();
    expect(first).toEqual({ imported: 3, skipped: 0, unsynced: 0 });
    expect(firstSyncMock).toHaveBeenCalledTimes(1);

    vi.stubGlobal("fetch", fetchMock);
    const second = await syncGuestAttempts();
    expect(second).toEqual({ imported: 0, skipped: 0, unsynced: 0 });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("posts local attempts in one batch and records the migrated ids", async () => {
    await seedAttempts(3);
    setAccessToken("access-token");
    const batches: number[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as { attempts: unknown[] };
        batches.push(body.attempts.length);
        return upgradeOk(body as { attempts: Array<{ attemptId: string }> });
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await syncGuestAttempts();
    expect(result).toEqual({ imported: 3, skipped: 0, unsynced: 0 });
    expect(batches).toEqual([3]);
    // 增量标记记录全部三条已迁移 id；欠账状态被清除。
    expect(readGuestUpgradeState()).toEqual({
      migratedIds: [attemptIdAt(0), attemptIdAt(1), attemptIdAt(2)],
      unsynced: 0,
    });
    expect(window.localStorage.getItem("yilian.guest-upgrade.v1")).toBeNull();
    expect(JSON.parse(window.localStorage.getItem("yilian.guest-upgraded.v1") ?? "{}")).toEqual({
      migratedIds: [attemptIdAt(0), attemptIdAt(1), attemptIdAt(2)],
    });
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
        return upgradeOk(body);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await syncGuestAttempts();
    expect(result).toEqual({ imported: 150, skipped: 0, unsynced: 0 });
    expect(batchSizes).toEqual([100, 50]);
    // clientCreatedAt 升序：RETRY 的原始提交（index 9 的前一条）先于改错入库。
    expect(firstIds[0]).toBe(attemptIdAt(0));
    expect(firstIds[1]).toBe(attemptIdAt(100));
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
        return upgradeOk(body as { attempts: Array<{ attemptId: string }> });
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await syncGuestAttempts();
    // 第一批 100 条成功入标记，第二批 50 条跳过（欠账）；完成态不再是一刀切布尔。
    expect(result).toEqual({ imported: 100, skipped: 0, unsynced: 50 });
    const state = readGuestUpgradeState();
    expect(state.unsynced).toBe(50);
    expect(state.migratedIds).toHaveLength(100);
    expect(state.migratedIds).not.toContain(attemptIdAt(149));
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
    // 网络失败立即中止：150 条全部未同步，无任何批进入增量标记。
    expect(result).toEqual({ imported: 0, skipped: 0, unsynced: 150 });
    expect(readGuestUpgradeState()).toEqual({ migratedIds: [], unsynced: 150 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("keeps local data after successful sync and records the migrated ids", async () => {
    await seedAttempts(3);
    setAccessToken("access-token");
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as { attempts: Array<{ attemptId: string }> };
        return upgradeOk(body);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    await syncGuestAttempts();
    // 本地记录不清理（IndexedDB 保留，可导出可重置）：迁移完成只是冻结本地引擎的路由命中。
    const { listAttempts } = await import("@/lib/local-engine");
    await expect(listAttempts()).resolves.toHaveLength(3);
    expect(readGuestUpgradeState().migratedIds).toHaveLength(3);
    expect(window.localStorage.getItem("yilian.guest-upgraded.v1")).not.toBeNull();
  });

  it("migrates records created after the marker on the next login", async () => {
    await seedAttempts(3);
    setAccessToken("access-token");
    const batches: string[][] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as {
          attempts: Array<{ attemptId: string }>;
        };
        batches.push(body.attempts.map((attempt) => attempt.attemptId));
        return upgradeOk(body);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    await syncGuestAttempts();
    // 完成标记之后登出又练了两条：id 不在已迁移集合，重新登录的自动迁移必须捕获它们
    // （browser-smoke-wave4 缺陷：旧布尔标记让这批记录永远无法并入账号）。
    await appendAttempts(3, 2);

    // 只看重试轮发出的批：清掉首轮记录。
    batches.length = 0;
    const result = await syncGuestAttempts();
    // 只发新增两条，已迁移的三条不重发；标记增长到五条。
    expect(batches).toEqual([[attemptIdAt(3), attemptIdAt(4)]]);
    expect(result).toEqual({ imported: 2, skipped: 0, unsynced: 0 });
    const state = readGuestUpgradeState();
    expect(state.unsynced).toBe(0);
    expect(state.migratedIds).toEqual([
      attemptIdAt(0),
      attemptIdAt(1),
      attemptIdAt(2),
      attemptIdAt(3),
      attemptIdAt(4),
    ]);
  });

  it("keeps the skipped batch as debt and resends only the debt on retry", async () => {
    await seedAttempts(150);
    setAccessToken("access-token");
    let batchIndex = 0;
    const retryBatches: string[][] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as {
          attempts: Array<{ attemptId: string }>;
        };
        const current = batchIndex;
        batchIndex += 1;
        if (current === 0) {
          // 第一批（最旧的 100 条）含服务端不存在的题目：40402 整批回滚成欠账。
          return jsonError(404, errorEnvelope(40402));
        }
        retryBatches.push(body.attempts.map((attempt) => attempt.attemptId));
        return upgradeOk(body);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const first = await syncGuestAttempts();
    // 欠账 100 条不进标记，成功的 50 条进标记。
    expect(first).toEqual({ imported: 50, skipped: 0, unsynced: 100 });
    const afterFirst = readGuestUpgradeState();
    expect(afterFirst.unsynced).toBe(100);
    expect(afterFirst.migratedIds).toHaveLength(50);
    expect(afterFirst.migratedIds).not.toContain(attemptIdAt(0));

    // 重试只重发欠账批（已迁移 id 不重发）；先清掉首轮成功批的记录再看重试轮。
    retryBatches.length = 0;
    const second = await syncGuestAttempts();
    expect(second).toEqual({ imported: 100, skipped: 0, unsynced: 0 });
    expect(retryBatches).toEqual([Array.from({ length: 100 }, (_, index) => attemptIdAt(index))]);
    const afterSecond = readGuestUpgradeState();
    expect(afterSecond.unsynced).toBe(0);
    expect(afterSecond.migratedIds).toHaveLength(150);
  });

  it("treats the legacy boolean marker as no marker and re-marks in the new format", async () => {
    await seedAttempts(3);
    setAccessToken("access-token");
    // 旧版本写的是布尔字符串（历史缺陷：之后新增的记录被永久挡住）。
    window.localStorage.setItem("yilian.guest-upgraded.v1", "done");
    const batches: number[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/guest-upgrade")) {
        const body = JSON.parse(String(init?.body)) as { attempts: unknown[] };
        batches.push(body.attempts.length);
        return upgradeOk(body as { attempts: Array<{ attemptId: string }> });
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    // 旧标记不短路：整批重发一次（服务端同载荷幂等 skip），随后写入新格式标记。
    const result = await syncGuestAttempts();
    expect(result).toEqual({ imported: 3, skipped: 0, unsynced: 0 });
    expect(batches).toEqual([3]);
    expect(readGuestUpgradeState().migratedIds).toHaveLength(3);
    expect(() =>
      JSON.parse(window.localStorage.getItem("yilian.guest-upgraded.v1") ?? ""),
    ).not.toThrow();
  });
});
