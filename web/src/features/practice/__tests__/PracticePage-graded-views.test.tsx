import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { useSessionStore } from "@/features/auth/session";
import { PracticePage } from "@/features/practice/PracticePage";
import { createQueryClient } from "@/lib/query";
import type { PageResponse } from "@/lib/schemas/common";
import type { BankView } from "@/lib/schemas/content";
import type { AttemptResponse, PracticeSentence, Today } from "@/lib/schemas/practice";

/**
 * 027 批 · 判分结果呈现体验（AC-4 对位双视图 / AC-5 批注样式分化 / AC-6 已记录微反馈）。
 *
 * mock 策略与 PracticePage.test.tsx 同构：router 用最小桩（页面只用 Link/useNavigate/useSearch），
 * API 在模块边界整体替换；判分主链路本身由后端切片测试覆盖，这里聚焦结果区的呈现与交互。
 */

/** @/lib/api/practice 的桩；getToday 供 lib/query 的 todayQueryOptions 消费。 */
const practiceApi = vi.hoisted(() => ({
  getToday: vi.fn(),
  getNextPractice: vi.fn(),
  getAttempt: vi.fn(),
  submitAttempt: vi.fn(),
  appealAnnotation: vi.fn(),
  disputeAnnotation: vi.fn(),
  askTutor: vi.fn(),
  reviewAttempt: vi.fn(),
}));

vi.mock("@/lib/api/practice", () => practiceApi);

/** @/lib/api/content 的桩；选题面板模块导入不炸（用例不打开面板）。 */
const contentApi = vi.hoisted(() => ({
  getBanks: vi.fn(),
  getBankSentences: vi.fn(),
  getPickerSentences: vi.fn(),
}));

vi.mock("@/lib/api/content", () => contentApi);

/** 路由 search 的可变桩：缺省空（新题流），回看用例注入 attemptId。 */
const routerState = vi.hoisted(() => ({ search: {} as Record<string, unknown> }));

/** 最小 router 桩；href 仅为过 a11y 检查。navigate 会把 search 写回可变桩，
 * 让「提交成功 → 带 attemptId 回跳」的链路在桩内闭合（真实路由由 TanStack 完成）。 */
vi.mock("@tanstack/react-router", () => ({
  Link: (props: { to?: string; search?: unknown; className?: string; children?: ReactNode }) => (
    <a href="#stub" className={props.className}>
      {props.children}
    </a>
  ),
  useNavigate: () => async (opts?: { search?: Record<string, unknown> }) => {
    if (opts && typeof opts === "object" && opts.search) {
      routerState.search = opts.search;
    }
  },
  useSearch: () => routerState.search,
}));

/** 基础句（新题流用例）：一空填空句，字段只要页面消费到的最小集。 */
const SENTENCE: PracticeSentence = {
  id: "01JSEN00000000000000000001",
  version: 1,
  sentenceVersionId: "01JVER00000000000000000001",
  questionVersion: 1,
  status: "PUBLISHED",
  deleted: false,
  zh: "他越来越重视环境保护。",
  ref: "He attaches more and more importance to environmental protection.",
  note: null,
  level: "CET6",
  chunks: [
    { index: 0, zh: "他越来越重视", answers: ["attaches"], tag: null, note: null },
    { index: 1, zh: "环境保护", answers: ["importance"], tag: null, note: null },
  ],
  blank: [1],
  tags: ["attach importance to"],
};

/** 缺省公开题库：让会话条与「题库」入口有上下文。 */
const BANK: BankView = {
  id: "7",
  ownerId: null,
  name: "官方题库",
  description: null,
  visibility: "PUBLIC",
  status: "PUBLISHED",
  version: 1,
  sentenceCount: 1,
};

/** 今日桩：dueCount 归零，避免与批注编号在 getByText 查询中相撞。 */
const TODAY: Today = {
  date: "2026-11-06",
  timezone: "Asia/Shanghai",
  dueCount: 0,
  doneCount: 0,
  goal: 10,
  correctRate: null,
  streakDays: 3,
};

/** 服务端首判 61 分桩：提交成功后的 mutation 应答（内容不影响乐观态展示）。 */
const SUBMIT_OK: AttemptResponse = {
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0001",
  sentenceId: SENTENCE.id,
  sentenceVersionId: SENTENCE.sentenceVersionId,
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
    summary: "全部命中",
  },
  grading: {
    id: "01HGRADING0000000000000001",
    sequence: 1,
    grader: "LOCAL",
    normalizerVersion: "norm-1",
    rubricVersion: "rubric-1",
    createdAt: "2026-11-06T04:00:00Z",
  },
  review: null,
  reference: { ref: SENTENCE.ref, note: null },
  nextAction: { retryRequired: false, canReveal: true },
  replayed: false,
};

/**
 * 挂载练习页（页面自身用 react-query，包 QueryClient 即可）。
 * @returns RTL 句柄
 */
function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <PracticePage />
    </QueryClientProvider>,
  );
}

/** 多词块填空句：blank 为三词块「pay attention to」，复现 D4 的多词强拆错位场景。 */
const MULTI_SENTENCE: PracticeSentence = {
  id: "01JSEN00000000000000000002",
  version: 1,
  sentenceVersionId: "01JVER00000000000000000002",
  questionVersion: 1,
  status: "PUBLISHED",
  deleted: false,
  zh: "你应该注意细节。",
  ref: "You should pay attention to details.",
  note: null,
  level: "CET4",
  chunks: [
    { index: 0, zh: "你应该", answers: ["should"], tag: null, note: null },
    { index: 1, zh: "注意", answers: ["pay attention to"], tag: "动词搭配", note: null },
    { index: 2, zh: "细节", answers: ["details"], tag: null, note: null },
  ],
  blank: [1],
  tags: ["动词搭配"],
};

/** 多词块判分桩：用户把「is paying attention to」塞进了三词块，整块未命中。 */
const MULTI_ATTEMPT: AttemptResponse = {
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0002",
  sentenceId: MULTI_SENTENCE.id,
  sentenceVersionId: MULTI_SENTENCE.sentenceVersionId,
  mode: "CLOZE",
  kind: "FIRST",
  score: 0,
  maxScore: 100,
  result: {
    schemaVersion: 1,
    score: 0,
    maxScore: 100,
    hitCount: 0,
    totalCount: 3,
    annotations: [
      {
        key: "slot:1",
        chunkIndex: 1,
        tag: "动词搭配",
        expected: ["pay attention to"],
        received: "is paying attention to",
        correct: false,
        message: "动词搭配错误",
        minimalFix: "pay attention to",
      },
    ],
    summary: "还差 1 处",
  },
  grading: {
    id: "01HGRADING0000000000000002",
    sequence: 1,
    grader: "LOCAL",
    normalizerVersion: "norm-1",
    rubricVersion: "rubric-1",
    createdAt: "2026-11-06T04:00:00Z",
  },
  review: null,
  reference: { ref: MULTI_SENTENCE.ref, note: null },
  nextAction: { retryRequired: true, canReveal: true },
  replayed: false,
};

/** 双空填空句：第一空答对、第二空答错，用于批注样式分化断言。 */
const TWO_BLANK_SENTENCE: PracticeSentence = {
  id: "01JSEN00000000000000000004",
  version: 1,
  sentenceVersionId: "01JVER00000000000000000004",
  questionVersion: 1,
  status: "PUBLISHED",
  deleted: false,
  zh: "他越来越重视环境保护。",
  ref: "He attaches more and more importance to environmental protection.",
  note: null,
  level: "CET6",
  chunks: [
    { index: 0, zh: "他越来越重视", answers: ["attaches"], tag: "动词搭配", note: null },
    { index: 1, zh: "环境保护", answers: ["importance to"], tag: "介词搭配", note: null },
  ],
  blank: [0, 1],
  tags: ["attach importance to"],
};

/** 双空判分桩：slot:0 全对（correct:true），slot:1 介词错误。 */
const SPLIT_ATTEMPT: AttemptResponse = {
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0004",
  sentenceId: TWO_BLANK_SENTENCE.id,
  sentenceVersionId: TWO_BLANK_SENTENCE.sentenceVersionId,
  mode: "CLOZE",
  kind: "FIRST",
  score: 50,
  maxScore: 100,
  result: {
    schemaVersion: 1,
    score: 50,
    maxScore: 100,
    hitCount: 1,
    totalCount: 2,
    annotations: [
      {
        key: "slot:0",
        chunkIndex: 0,
        tag: "动词搭配",
        expected: ["attaches"],
        received: "attaches",
        correct: true,
        message: null,
        minimalFix: null,
      },
      {
        key: "slot:1",
        chunkIndex: 1,
        tag: "介词搭配",
        expected: ["importance to"],
        received: "importance on",
        correct: false,
        message: "介词搭配不正确",
        minimalFix: "importance to",
      },
    ],
    summary: "还差 1 处",
  },
  grading: {
    id: "01HGRADING0000000000000004",
    sequence: 1,
    grader: "LOCAL",
    normalizerVersion: "norm-1",
    rubricVersion: "rubric-1",
    createdAt: "2026-11-06T04:00:00Z",
  },
  review: null,
  reference: { ref: TWO_BLANK_SENTENCE.ref, note: null },
  nextAction: { retryRequired: true, canReveal: true },
  replayed: false,
};

/** 整句句：两个要点语块，用户命中「充分利用」、漏掉「the time」的限定。 */
const FREE_SENTENCE: PracticeSentence = {
  id: "01JSEN00000000000000000003",
  version: 1,
  sentenceVersionId: "01JVER00000000000000000003",
  questionVersion: 1,
  status: "PUBLISHED",
  deleted: false,
  zh: "我们应该充分利用时间。",
  ref: "We should make full use of the time.",
  note: null,
  level: "CET4",
  chunks: [
    { index: 0, zh: "充分利用", answers: ["make full use of"], tag: null, note: null },
    { index: 1, zh: "时间", answers: ["the time"], tag: null, note: null },
  ],
  blank: [],
  tags: ["make use of"],
};

/** 整句判分桩：free:0 聚合批注（服务端/本地同形），received 承载用户完整译文。 */
const FREE_ATTEMPT: AttemptResponse = {
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0003",
  sentenceId: FREE_SENTENCE.id,
  sentenceVersionId: FREE_SENTENCE.sentenceVersionId,
  mode: "FREE",
  kind: "FIRST",
  score: 75,
  maxScore: 100,
  result: {
    schemaVersion: 1,
    score: 75,
    maxScore: 100,
    hitCount: 5,
    totalCount: 7,
    annotations: [
      {
        key: "free:0",
        chunkIndex: 0,
        tag: "整句",
        expected: [FREE_SENTENCE.ref],
        received: "We should make full use of time.",
        correct: false,
        message: "与参考译文有差异",
        minimalFix: FREE_SENTENCE.ref,
      },
    ],
    summary: "要点覆盖 75%",
  },
  grading: {
    id: "01HGRADING0000000000000003",
    sequence: 1,
    grader: "LOCAL",
    normalizerVersion: "norm-1",
    rubricVersion: "rubric-1",
    createdAt: "2026-11-06T04:00:00Z",
  },
  review: null,
  reference: { ref: FREE_SENTENCE.ref, note: null },
  nextAction: { retryRequired: true, canReveal: true },
  replayed: false,
};

/**
 * 挂载回看场景（任意句/任意判分）：路由带 attemptId、题干快照就位、getAttempt 回指定判分。
 * @param sentence 题干快照
 * @param attempt getAttempt 返回的判分结果
 * @returns RTL 句柄
 */
async function renderAttemptFor(sentence: PracticeSentence, attempt: AttemptResponse) {
  routerState.search = { attemptId: attempt.attemptId };
  sessionStorage.setItem(`yilian.practice.sentence.${sentence.id}`, JSON.stringify(sentence));
  practiceApi.getAttempt.mockResolvedValue(attempt);
  const view = renderPage();
  // 等判分视图渲染完成：「参考译文」标签只在结果区出现，且不与到期数/编号等数字查询相撞。
  await screen.findByText("参考译文");
  return view;
}

describe("PracticePage 判分呈现体验（027）", () => {
  beforeAll(() => {
    // jsdom 未实现 scrollIntoView；useFocusScroll 在输入聚焦后会调用它。
    Element.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
    routerState.search = {};
    useSessionStore.setState({ status: "anonymous" });
    practiceApi.getToday.mockResolvedValue(TODAY);
    practiceApi.getNextPractice.mockResolvedValue({
      sentence: SENTENCE,
      review: null,
      selection: "NEW",
    });
    practiceApi.getAttempt.mockResolvedValue(null);
    practiceApi.submitAttempt.mockReset();
    practiceApi.reviewAttempt.mockReset();
    contentApi.getBanks.mockResolvedValue({
      items: [BANK],
      page: 1,
      size: 100,
      total: 1,
      totalPages: 1,
      hasNext: false,
    } satisfies PageResponse<BankView>);
    contentApi.getBankSentences.mockResolvedValue({
      version: 1,
      items: [SENTENCE],
      nextSinceVersion: 0,
      hasMore: false,
    });
  });

  /** [AC-4] 双视图切换：默认保持现状视图，参考对位视图逐词可读、用户输入不丢字。 */
  it("dualViewToggle_ac4: 默认按我的输入，切参考对位后逐词命中可读且朱批退场", async () => {
    const view = await renderAttemptFor(MULTI_SENTENCE, MULTI_ATTEMPT);

    // 默认视图 = 现状：切换行出现且「按我的输入」选中；行内朱批（期望文本悬浮）仍在
    // ——「pay attention to」出现两处：骨架行内朱批 + 批注栏「正确写法」。
    expect(screen.getByRole("button", { name: "按我的输入" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "按参考对位" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
    expect(screen.getAllByText("pay attention to")).toHaveLength(2);

    await userEvent.setup().click(screen.getByRole("button", { name: "按参考对位" }));

    // 期望词逐词展开：pay 未命中（朱红），attention/to 经兜底匹配命中（苔绿）——
    // 直接回答「到底错哪个词」；完整输入保留，不再被固定词框强拆丢字。
    expect(screen.getByText("pay")).toHaveClass("text-vermilion");
    expect(screen.getByText("attention")).toHaveClass("text-moss");
    expect(screen.getByText("to")).toHaveClass("text-moss");
    expect(screen.getByText("你写：is paying attention to")).toBeInTheDocument();
    // 参考对位视图下行内朱批退场：只剩批注栏「正确写法」一处。
    expect(screen.getAllByText("pay attention to")).toHaveLength(1);

    // 切回默认视图：现状渲染恢复（两处都在）。
    await userEvent.setup().click(screen.getByRole("button", { name: "按我的输入" }));
    expect(screen.getAllByText("pay attention to")).toHaveLength(2);
    expect(screen.queryByText("你写：is paying attention to")).toBeNull();
    view.unmount();
  });

  /** [AC-4] 全对不出现切换行：两视图无差异时不给达标反馈添噪音。 */
  it("noDualViewToggleWhenAllHit_ac4", async () => {
    const allHit: AttemptResponse = {
      ...MULTI_ATTEMPT,
      score: 100,
      result: {
        ...MULTI_ATTEMPT.result,
        score: 100,
        hitCount: 3,
        annotations: [
          {
            key: "slot:1",
            chunkIndex: 1,
            tag: "动词搭配",
            expected: ["pay attention to"],
            received: "pay attention to",
            correct: true,
            message: null,
            minimalFix: null,
          },
        ],
        summary: "全部命中",
      },
    };
    const view = await renderAttemptFor(MULTI_SENTENCE, allHit);
    expect(screen.queryByRole("button", { name: "按我的输入" })).toBeNull();
    view.unmount();
  });

  /** [AC-5] 填空批注分化：命中的空用苔绿对勾 + 中性正文，申诉入口只出现在错误条目上。 */
  it("annotationStyleSplit_ac5: 答对语块不再以错误条目样式混入", async () => {
    const view = await renderAttemptFor(TWO_BLANK_SENTENCE, SPLIT_ATTEMPT);

    // 正确条目：苔绿对勾替代朱红编号，正文给中性「已写对」（无 message 时的兜底文案）。
    const check = screen.getByText("✓");
    expect(check).toHaveClass("text-moss");
    expect(screen.getByText("已写对")).toBeInTheDocument();
    // 错误条目：朱红编号保留。圈定批注列表查询，避免与题干挖空序号角标相撞。
    const annotations = screen.getByRole("list");
    expect(within(annotations).getByText("2")).toHaveClass("text-vermilion");
    // 申诉入口只在错误条目上出现一次。
    expect(within(annotations).getAllByRole("button", { name: "我觉得也对" })).toHaveLength(1);
    view.unmount();
  });

  /** [AC-5] 整句聚合批注分化：命中的语块先确认（苔绿），参考写法只列未命中的语块。 */
  it("freeAnnotationSplit_ac5: 整句批注区分命中与未命中语块", async () => {
    const view = await renderAttemptFor(FREE_SENTENCE, FREE_ATTEMPT);

    expect(screen.getByText("「充分利用」已写对。")).toHaveClass("text-moss");
    expect(screen.getByText("未命中的语块参考写法：")).toBeInTheDocument();
    expect(screen.getByText("the time")).toBeInTheDocument();
    // 聚合批注仍是错误条目：申诉入口保留。
    expect(screen.getByRole("button", { name: "我觉得也对" })).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-6] 练习判分落定后出现「已记录」微反馈（乐观渲染即时可见，1.8s 自动消失）。 */
  it("recordedFlashOnGradedResult_ac6: 判分落定出现已记录确认", async () => {
    practiceApi.submitAttempt.mockResolvedValue(SUBMIT_OK);
    // 提交成功后页面带 attemptId 回跳，attemptQuery 重取服务端判分接住结果视图。
    practiceApi.getAttempt.mockResolvedValue(SUBMIT_OK);
    renderPage();

    const input = await screen.findByLabelText("第 1 空第 1 词：环境保护");
    await userEvent.setup().type(input, "importance");
    await userEvent.setup().click(screen.getByRole("button", { name: /提交/ }));

    // 微反馈与结果分同时就位（乐观态即时渲染，不再「答了没回音」）。
    expect(await screen.findByText("已记录 ✓")).toBeInTheDocument();
    expect(await screen.findByText("100")).toBeInTheDocument();
    // 微反馈短暂停留后自动消失（1.8s，与 toast 同节奏）。
    await waitFor(() => expect(screen.queryByText("已记录 ✓")).toBeNull(), { timeout: 3000 });
  });
});
