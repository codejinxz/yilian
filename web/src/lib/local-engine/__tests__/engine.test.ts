/**
 * 本地练习引擎的单元测试：全部通过注入内存存储后端运行（jsdom 没有 IndexedDB），
 * 覆盖幂等、排程（错→明天 / 两全对→归档 / 看答案入队不计正确率）、降载与顺延、
 * 今日/统计聚合与「不认同」排除。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AttemptRequest, PracticeSentence } from "@/lib/schemas/practice";
import {
  clearLocalData,
  createMemoryEngineStorage,
  type EngineStorage,
  exportLocalData,
  getAttemptLocal,
  getCachedSentences,
  getDeviceId,
  getOverloadState,
  getPlacementSentences,
  getReviewQueue,
  getStatsSnapshot,
  getTodaySnapshot,
  getWrongQuestions,
  loadPlacementState,
  pickNext,
  postponeOverdue,
  refreshBankCache,
  resetPlacementState,
  resetReviewState,
  type StoredReview,
  savePlacementState,
  setAnnotationDispute,
  setEngineStorageForTests,
  submitLocalAttempt,
} from "..";

const DAY_MS = 86_400_000;

let backend: EngineStorage;

/**
 * 构造可练题目；chunks 以 [中文, 答案, 考点] 三元组描述，全部单可接受答案。
 * @param id 题目 id
 * @param ref 参考译文
 * @param chunks 语块定义
 * @param blank 挖空下标
 * @param tags 考点标签
 * @returns 题目对象
 */
function makeSentence(
  id: string,
  ref: string,
  chunks: Array<[string, string, string]>,
  blank: number[],
  tags: string[],
): PracticeSentence {
  return {
    id,
    version: 1,
    sentenceVersionId: `v-${id}`,
    questionVersion: 1,
    status: "PUBLISHED",
    deleted: false,
    zh: `中文题干 ${id}`,
    ref,
    note: null,
    chunks: chunks.map(([chunkZh, answer, tag], index) => ({
      index,
      zh: chunkZh,
      answers: [answer],
      tag,
      note: null,
    })),
    blank,
    tags,
    level: "CET6",
  };
}

/** 基础三句 + 程序化补齐到 10 句，覆盖降载（>8 到期）场景。 */
const SENTENCES: PracticeSentence[] = [
  makeSentence(
    "s1",
    "He is paying more and more attention to this.",
    [
      ["他", "He", "代词"],
      ["越来越", "more and more", "副词结构"],
      ["重视这件事", "pays attention to this", "搭配"],
    ],
    [1, 2],
    ["副词结构", "搭配"],
  ),
  makeSentence(
    "s2",
    "This policy has the greatest impact on small firms.",
    [
      ["这项政策", "This policy", "名词短语"],
      ["影响最大", "has the greatest impact", "搭配"],
      ["对小企业", "on small firms", "介词结构"],
    ],
    [1, 2],
    ["搭配", "介词结构"],
  ),
  makeSentence(
    "s3",
    "Despite the difficulties, she still stuck to her view.",
    [
      ["尽管困难重重", "Despite the difficulties", "让步结构"],
      ["她仍然坚持", "she still stuck to", "动词短语"],
      ["自己的观点", "her own view", "名词短语"],
    ],
    [1, 2],
    ["动词短语", "让步结构"],
  ),
  ...Array.from({ length: 7 }, (_, index) =>
    makeSentence(
      `s${index + 4}`,
      `Sentence number ${index + 4} goes here.`,
      [["占位", `word${index + 4}`, "测试考点"]],
      [0],
      ["测试考点"],
    ),
  ),
];

/** s1 的全对答案。 */
const S1_CORRECT = { 1: "more and more", 2: "pays attention to this" };
/** s2 的全对答案。 */
const S2_CORRECT = { 1: "has the greatest impact", 2: "on small firms" };

/**
 * 构造合法的填空 attempt 请求。
 * @param sentenceId 题目 id
 * @param values 按 chunkIndex 的作答
 * @param overrides 覆盖字段（kind/retryOfId/answer 等）
 * @returns attempt 请求
 */
function buildAttempt(
  sentenceId: string,
  values: Record<number, string>,
  overrides: Partial<AttemptRequest> = {},
): AttemptRequest {
  const sentence = SENTENCES.find((item) => item.id === sentenceId) as PracticeSentence;
  return {
    attemptId: crypto.randomUUID(),
    sentenceId,
    sentenceVersionId: sentence.sentenceVersionId,
    mode: "CLOZE",
    kind: "FIRST",
    retryOfId: null,
    answer: {
      schemaVersion: 1,
      mode: "CLOZE",
      slots: sentence.blank.map((chunkIndex) => ({
        chunkIndex,
        value: values[chunkIndex] ?? "",
      })),
    },
    hintsUsed: 0,
    durationMs: 30_000,
    ...overrides,
  };
}

/**
 * 直接把句子写入题库缓存；多数用例不关心拉取过程，绕开 fetch 桩。
 * @param sentences 题目列表，缺省为全量夹具
 */
async function seedBank(sentences: PracticeSentence[] = SENTENCES): Promise<void> {
  const store = await backend.bankCache();
  await store.put("1", {
    bankId: "1",
    sentences: sentences.map((sentence) => ({ ...sentence })),
    fetchedAt: new Date().toISOString(),
  });
}

/**
 * 把所有学习中卡片回拨为已到期；测试降载/到期优先必须让 dueAt 落在过去。
 * @param daysAgo 回拨天数
 */
async function backdateCards(daysAgo = 1): Promise<void> {
  const store = await backend.reviews();
  for (const card of await store.getAll()) {
    await store.put(card.sentenceId, {
      ...card,
      dueAt: new Date(Date.now() - daysAgo * DAY_MS).toISOString(),
    });
  }
}

/**
 * 构造返回统一信封的 fetch 响应。
 * @param data 信封 data
 * @returns 伪 Response
 */
function jsonOk(data: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: 0, message: "success", data }),
  } as Response;
}

/**
 * 桩掉 fetch：/banks 列表返回一个公开题库，/banks/1/sentences 返回夹具句子。
 * @param sentences 题目列表
 */
function stubBankFetch(sentences: PracticeSentence[]): void {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/banks?")) {
        return jsonOk({
          items: [
            {
              id: "1",
              ownerId: null,
              name: "官方题库",
              description: null,
              visibility: "PUBLIC",
              status: "PUBLISHED",
              version: 1,
              sentenceCount: sentences.length,
            },
          ],
          page: 1,
          size: 100,
          total: 1,
          totalPages: 1,
          hasNext: false,
        });
      }
      return jsonOk({
        version: 1,
        items: sentences.map((sentence) => ({ ...sentence })),
        nextSinceVersion: 1,
        hasMore: false,
      });
    }),
  );
}

beforeEach(() => {
  backend = createMemoryEngineStorage();
  setEngineStorageForTests(backend);
});

afterEach(() => {
  setEngineStorageForTests(null);
  vi.unstubAllGlobals();
});

describe("本地引擎：设备号与定级状态", () => {
  it("设备号首次生成后保持稳定", () => {
    const first = getDeviceId();
    const second = getDeviceId();
    expect(first).toBe(second);
    expect(first.length).toBeGreaterThan(0);
  });

  it("定级状态读写往返，重置后为 null", () => {
    expect(loadPlacementState()).toBeNull();
    savePlacementState({ placed: true, startLevel: "中级·日常", missedTags: ["搭配"] });
    expect(loadPlacementState()).toEqual({
      placed: true,
      startLevel: "中级·日常",
      missedTags: ["搭配"],
    });
    resetPlacementState();
    expect(loadPlacementState()).toBeNull();
  });

  it("损坏的定级数据按未定级处理", () => {
    backend.kv.setItem("yilian.placement.v1", "{not-json");
    expect(loadPlacementState()).toBeNull();
  });
});

describe("本地引擎：题库缓存与定级题", () => {
  it("refreshBankCache 拉取公开题库并写入缓存", async () => {
    stubBankFetch(SENTENCES);
    await refreshBankCache();
    const cached = await getCachedSentences();
    expect(cached).toHaveLength(SENTENCES.length);
    expect((await getCachedSentences("1")).map((s) => s.id)).toEqual(SENTENCES.map((s) => s.id));
    expect(await getCachedSentences("missing")).toEqual([]);
  });

  it("拉取失败时保留旧缓存（离线降级）", async () => {
    await seedBank();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("network down");
      }),
    );
    await expect(refreshBankCache()).rejects.toThrow();
    expect(await getCachedSentences()).toHaveLength(SENTENCES.length);
  });

  it("getPlacementSentences 均匀取首中尾 3 道；无数据时 reject", async () => {
    await seedBank();
    const placement = await getPlacementSentences();
    expect(placement.map((s) => s.id)).toEqual(["s1", "s6", "s10"]);

    // 换一个空后端并让拉取失败：应 reject 由页面显示重试态。
    backend = createMemoryEngineStorage();
    setEngineStorageForTests(backend);
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("network down");
      }),
    );
    await expect(getPlacementSentences()).rejects.toThrow("题目缓存");
  });
});

describe("本地引擎：提交幂等与排程", () => {
  it("同一 attemptId 复提交返回原结果并标记 replayed", async () => {
    await seedBank();
    const request = buildAttempt("s1", { 1: "totally wrong", 2: "nope" });
    const first = await submitLocalAttempt(request);
    const second = await submitLocalAttempt(request);
    expect(first.replayed).toBe(false);
    expect(second.replayed).toBe(true);
    expect(second.score).toBe(first.score);
    expect(second.result).toEqual(first.result);
    const stats = await getStatsSnapshot();
    expect(stats.totalAttempts).toBe(1);
  });

  it("答错置 LEARNING、明天到期，且尚未计入今日到期", async () => {
    await seedBank();
    const response = await submitLocalAttempt(
      buildAttempt("s1", { 1: "totally wrong", 2: "nope" }),
    );
    expect(response.score).toBeLessThan(100);
    expect(response.nextAction.retryRequired).toBe(true);
    const card = response.review as StoredReview;
    expect(card.state).toBe("LEARNING");
    const dueAt = new Date(card.dueAt).getTime();
    expect(dueAt).toBeGreaterThan(Date.now() + DAY_MS - 60_000);
    expect(card.scheduledDays).toBe(1);
    const today = await getTodaySnapshot();
    expect(today.dueCount).toBe(0);
    expect(today.doneCount).toBe(1);
  });

  it("两全对后归档：间隔 1→3→5 触发 MASTERED", async () => {
    await seedBank();
    const first = await submitLocalAttempt(buildAttempt("s1", { 1: "wrong", 2: "wrong" }));
    const firstCard = first.review as StoredReview;
    expect(firstCard.state).toBe("LEARNING");
    expect(firstCard.scheduledDays).toBe(1);

    const second = await submitLocalAttempt(
      buildAttempt("s1", S1_CORRECT, { attemptId: crypto.randomUUID() }),
    );
    const secondCard = second.review as StoredReview;
    expect(secondCard.state).toBe("LEARNING");
    expect(secondCard.scheduledDays).toBe(3);

    const third = await submitLocalAttempt(
      buildAttempt("s1", S1_CORRECT, { attemptId: crypto.randomUUID() }),
    );
    const thirdCard = third.review as StoredReview;
    expect(thirdCard.state).toBe("MASTERED");
    expect(thirdCard.scheduledDays).toBe(5);

    const queue = await getReviewQueue();
    expect(queue.summary.mastered).toBe(1);
    expect(queue.summary.learning).toBe(0);
  });

  it("新题全对保持 NEW，不入复习队列", async () => {
    await seedBank();
    const response = await submitLocalAttempt(buildAttempt("s2", S2_CORRECT));
    expect(response.score).toBe(100);
    expect(response.review).toBeNull();
    const queue = await getReviewQueue();
    expect(queue.rows).toHaveLength(0);
  });

  it("看答案（REVEAL）入队但不计正确率", async () => {
    await seedBank();
    const response = await submitLocalAttempt(
      buildAttempt(
        "s3",
        {},
        {
          kind: "REVEAL",
          answer: { schemaVersion: 1, mode: "CLOZE", revealed: true, slots: [] },
        },
      ),
    );
    expect(response.score).toBeNull();
    expect(response.nextAction.canReveal).toBe(false);
    const card = response.review as StoredReview;
    expect(card.state).toBe("LEARNING");
    expect(card.scheduledDays).toBe(1);

    await submitLocalAttempt(buildAttempt("s2", S2_CORRECT, { attemptId: crypto.randomUUID() }));
    const today = await getTodaySnapshot();
    expect(today.doneCount).toBe(2);
    // 看答案不参与正确率：只对 s2 计分且全对 → 100。
    expect(today.correctRate).toBe(100);
    const stats = await getStatsSnapshot();
    expect(stats.averageScore).toBe(100);
  });

  it("REVEAL 未标记 revealed 时拒绝", async () => {
    await seedBank();
    await expect(
      submitLocalAttempt(
        buildAttempt("s3", {}, { kind: "REVEAL", answer: { schemaVersion: 1, mode: "CLOZE" } }),
      ),
    ).rejects.toThrow("revealed");
  });

  it("RETRY 必须指向同题同模式的原始提交，且不改变排程", async () => {
    await seedBank();
    const original = await submitLocalAttempt(buildAttempt("s1", { 1: "wrong", 2: "wrong" }));
    const before = (original.review as StoredReview).dueAt;
    const retry = await submitLocalAttempt(
      buildAttempt("s1", S1_CORRECT, {
        kind: "RETRY",
        retryOfId: original.attemptId,
      }),
    );
    expect(retry.score).toBe(100);
    expect((retry.review as StoredReview).dueAt).toBe(before);
    await expect(
      submitLocalAttempt(
        buildAttempt("s2", S2_CORRECT, { kind: "RETRY", retryOfId: original.attemptId }),
      ),
    ).rejects.toThrow("同一题");
  });

  it("getAttemptLocal 返回已存结果，未知 id 返回 null", async () => {
    await seedBank();
    const response = await submitLocalAttempt(buildAttempt("s1", S1_CORRECT));
    const stored = await getAttemptLocal(response.attemptId);
    expect(stored?.attemptId).toBe(response.attemptId);
    expect(await getAttemptLocal(crypto.randomUUID())).toBeNull();
  });
});

describe("本地引擎：拉题与降载", () => {
  it("默认按题库顺序取新题，excludeIds 生效", async () => {
    await seedBank();
    expect((await pickNext())?.id).toBe("s1");
    expect((await pickNext({ excludeIds: ["s1"] }))?.id).toBe("s2");
  });

  it("到期卡优先于题库顺序，且可被 excludeIds 排除", async () => {
    await seedBank();
    await submitLocalAttempt(buildAttempt("s1", { 1: "wrong", 2: "wrong" }));
    await backdateCards();
    expect((await pickNext())?.id).toBe("s1");
    expect((await pickNext({ excludeIds: ["s1"] }))?.id).toBe("s2");
  });

  it("到期召回同样受 tag 与 mode 筛选约束", async () => {
    await seedBank();
    // s1 答错 → CLOZE 卡到期，考点含「副词结构」（单键卡：mode=最近练习模式）。
    await submitLocalAttempt(buildAttempt("s1", { 1: "wrong", 2: "wrong" }));
    await backdateCards();
    // 考点筛选：s1 不含「让步结构」，到期召回跳过它，新题循环命中 s3。
    expect((await pickNext({ tag: "让步结构" }))?.id).toBe("s3");
    expect((await pickNext({ tag: "副词结构" }))?.id).toBe("s1");
    // 模式筛选：显式传 FREE 时，CLOZE 练出的到期卡不被召回。
    expect((await pickNext({ mode: "CLOZE" }))?.id).toBe("s1");
    expect((await pickNext({ mode: "FREE" }))?.id).toBe("s2");
  });

  it("题库响应中的墓碑行被丢弃，不影响缓存写入", async () => {
    // 后端对已删/停用句按 API §1.7 输出显式 null 字段的墓碑，会让共享 schema 的
    // 整包解析失败；引擎在解析前丢弃 deleted=true 行，缓存仍应写入其余句子。
    const tombstoned = {
      ...SENTENCES[0],
      status: "DISABLED",
      deleted: true,
      zh: null,
      ref: null,
      questionVersion: null,
      sentenceVersionId: null,
      chunks: null,
      blank: null,
      tags: null,
    } as unknown as PracticeSentence;
    stubBankFetch([tombstoned, ...SENTENCES.slice(1)]);
    await refreshBankCache();
    const cached = await getCachedSentences();
    expect(cached.map((item) => item.id)).not.toContain("s1");
    expect(cached).toHaveLength(SENTENCES.length - 1);
    expect((await pickNext())?.id).toBe("s2");
  });

  it("题库为空或全部排完时返回 null", async () => {
    expect(await pickNext()).toBeNull();
    await seedBank();
    // 全部答错后都是 LEARNING 且未到期 → 无可练题。
    for (const sentence of SENTENCES) {
      await submitLocalAttempt(buildAttempt(sentence.id, { 0: "wrong", 1: "wrong", 2: "wrong" }));
    }
    expect(await pickNext()).toBeNull();
  });

  it("到期超过 8 进入降载，顺延保留最紧急 5 张", async () => {
    await seedBank();
    for (const sentence of SENTENCES) {
      await submitLocalAttempt(buildAttempt(sentence.id, { 0: "wrong", 1: "wrong", 2: "wrong" }));
    }
    await backdateCards();
    const overload = await getOverloadState();
    expect(overload.active).toBe(true);
    expect(overload.dueCount).toBe(10);

    const postponed = await postponeOverdue(3);
    expect(postponed).toBe(5);
    const after = await getOverloadState();
    expect(after.dueCount).toBe(5);
    expect(after.active).toBe(false);

    // 被顺延的卡 3 天后到期，不再是 LEARNING 未到期以外的状态。
    const queue = await getReviewQueue();
    expect(queue.summary.learning).toBe(10);
    const dueRows = queue.rows.filter((row) => new Date(row.review.dueAt).getTime() <= Date.now());
    expect(dueRows).toHaveLength(5);
  });

  it("resetReviewState 把卡片重置为 NEW", async () => {
    await seedBank();
    const response = await submitLocalAttempt(buildAttempt("s1", { 1: "wrong", 2: "wrong" }));
    const reset = await resetReviewState((response.review as StoredReview).id);
    expect(reset?.state).toBe("NEW");
    expect((reset as StoredReview).reps).toBe(0);
    expect(await resetReviewState("lr-missing")).toBeNull();
  });
});

describe("本地引擎：今日与统计聚合", () => {
  it("今日快照聚合完成数、正确率、目标与连续天数", async () => {
    await seedBank();
    await submitLocalAttempt(buildAttempt("s1", S1_CORRECT));
    await submitLocalAttempt(buildAttempt("s2", { 1: "nope", 2: "nope" }));
    const today = await getTodaySnapshot();
    expect(today.doneCount).toBe(2);
    expect(today.correctRate).toBe(50);
    expect(today.goal).toBe(3);
    expect(today.streakDays).toBe(1);
  });

  it("统计快照给出累计、平均分、14 天趋势与考点分布", async () => {
    await seedBank();
    await submitLocalAttempt(buildAttempt("s1", S1_CORRECT));
    await submitLocalAttempt(buildAttempt("s2", { 1: "nope", 2: "nope" }));
    const stats = await getStatsSnapshot();
    expect(stats.totalAttempts).toBe(2);
    expect(stats.averageScore).toBe(50);
    expect(stats.correctRate).toBe(50);
    expect(stats.streakDays).toBe(1);
    expect(stats.daily14).toHaveLength(14);
    expect(stats.daily14[13]).toBe(2);
    expect(stats.daily14[12]).toBe(0);
    // s2 两处都错：搭配与介词结构各计 1。
    const tagCounts = Object.fromEntries(stats.errorTags.map((item) => [item.tag, item.count]));
    expect(tagCounts).toEqual({ 搭配: 1, 介词结构: 1 });
    expect(stats.recent).toHaveLength(2);
    const byMode = stats.byMode.find((item) => item.mode === "CLOZE");
    expect(byMode?.attempts).toBe(2);
  });
});

describe("本地引擎：「不认同」排除", () => {
  it("dispute 后批注不再计入考点统计，错题行标记 disputed", async () => {
    await seedBank();
    const response = await submitLocalAttempt(buildAttempt("s1", { 1: "nope", 2: "nope" }));
    const incorrect = response.result.annotations.filter((item) => !item.correct);
    expect(incorrect.length).toBeGreaterThan(0);

    let stats = await getStatsSnapshot();
    expect(stats.errorTags.length).toBe(incorrect.length);

    // 逐条「不认同」：统计同步排除，判分记录与错题行保留 disputed 标记。
    for (const annotation of incorrect) {
      await setAnnotationDispute(response.attemptId, annotation.key);
    }
    stats = await getStatsSnapshot();
    expect(stats.errorTags).toEqual([]);

    const wrong = await getWrongQuestions();
    expect(wrong).toHaveLength(1);
    expect(wrong[0]?.annotations.every((item) => item.disputed)).toBe(true);
    expect(wrong[0]?.annotations.map((item) => item.key)).toEqual(
      incorrect.map((item) => item.key),
    );
  });

  it("最近一次全对视为已修复，退出错题列表", async () => {
    await seedBank();
    const wrong = await submitLocalAttempt(buildAttempt("s1", { 1: "nope", 2: "nope" }));
    expect(await getWrongQuestions()).toHaveLength(1);
    await submitLocalAttempt(buildAttempt("s1", S1_CORRECT, { attemptId: crypto.randomUUID() }));
    expect(await getWrongQuestions()).toHaveLength(0);
    expect(wrong.score).toBeLessThan(100);
  });
});

describe("本地引擎：导出与清空", () => {
  it("exportLocalData 汇总全部本地数据", async () => {
    await seedBank();
    await submitLocalAttempt(buildAttempt("s1", { 1: "nope", 2: "nope" }));
    const data = (await exportLocalData()) as Record<string, unknown>;
    expect(data.version).toBe(1);
    expect(Array.isArray(data.attempts)).toBe(true);
    expect((data.attempts as unknown[]).length).toBe(1);
    expect(Array.isArray(data.reviews)).toBe(true);
    expect(Array.isArray(data.bankCache)).toBe(true);
  });

  it("clearLocalData 清空记录、缓存与定级状态", async () => {
    await seedBank();
    savePlacementState({ placed: true, startLevel: null, missedTags: [] });
    await submitLocalAttempt(buildAttempt("s1", { 1: "nope", 2: "nope" }));
    await clearLocalData();
    expect(await getCachedSentences()).toEqual([]);
    expect(await getWrongQuestions()).toEqual([]);
    expect(loadPlacementState()).toBeNull();
  });
});
