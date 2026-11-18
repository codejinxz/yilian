import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { useSessionStore } from "@/features/auth/session";
import { PracticePage } from "@/features/practice/PracticePage";
import { createQueryClient } from "@/lib/query";
import type { PageResponse } from "@/lib/schemas/common";
import type { BankView } from "@/lib/schemas/content";
import type {
  AppealResponse,
  AttemptResponse,
  PracticeSentence,
  Today,
} from "@/lib/schemas/practice";

/**
 * 用户端申诉透明度 [AC-7]：申诉提交同步 LLM 初裁后，批注行按回执状态分化文案——
 * PENDING+有 AI 意见 → 「AI 意见：SUPPORT/OPPOSE 摘要 · 等待人工终审」；初裁不可用 →
 * 「AI 初裁暂不可用 · 等待人工终审」；无 AI 意见 → 原「申诉已记录」文案；
 * 终审 ACCEPTED → 「申诉已通过，该写法已加入可接受答案」；REJECTED → 「申诉未通过」；
 * DISPUTE 的「已排除」语义不变。
 *
 * mock 策略与 PracticePage-graded-views 同构：router 最小桩 + API 模块整体替换；回执夹具
 * 以服务端集成测试的真实断言为基准（修正 mock 漂移），mock 只替换传输层、
 * 响应形状锁定为真实契约。
 */

/** @/lib/api/practice 的桩。 */
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

const contentApi = vi.hoisted(() => ({
  getBanks: vi.fn(),
  getBankSentences: vi.fn(),
  getPickerSentences: vi.fn(),
}));

vi.mock("@/lib/api/content", () => contentApi);

/** 路由 search 的可变桩：回看用例注入 attemptId。 */
const routerState = vi.hoisted(() => ({ search: {} as Record<string, unknown> }));

vi.mock("@tanstack/react-router", () => ({
  Link: (props: { to?: string; search?: unknown; className?: string; children?: ReactNode }) => (
    <a href="#stub" className={props.className}>
      {props.children}
    </a>
  ),
  useNavigate: () => async () => {},
  useSearch: () => routerState.search,
}));

/** 一空填空句：可答错并申诉。 */
const SENTENCE: PracticeSentence = {
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

const TODAY: Today = {
  date: "2026-11-06",
  timezone: "Asia/Shanghai",
  dueCount: 0,
  doneCount: 0,
  goal: 10,
  correctRate: null,
  streakDays: 3,
};

/** 服务端判分桩：slot:1 整块未命中（申诉对象）。 */
const ATTEMPT: AttemptResponse = {
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0002",
  sentenceId: SENTENCE.id,
  sentenceVersionId: SENTENCE.sentenceVersionId,
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
  reference: { ref: SENTENCE.ref, note: null },
  nextAction: { retryRequired: true, canReveal: true },
  replayed: false,
};

/** 申诉回执夹具工厂。 */
function appealResponse(overrides: Partial<AppealResponse> = {}): AppealResponse {
  return {
    appealId: "60",
    status: "PENDING",
    kind: "APPEAL",
    annotationKey: "slot:1",
    ...overrides,
  };
}

/**
 * 挂载练习页。
 * @returns RTL 句柄
 */
function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <PracticePage />
    </QueryClientProvider>,
  );
}

/**
 * 以回看视图挂载（带判分结果），点击错误批注上的「我觉得也对」展开申诉表单。
 */
async function openAppealForm() {
  routerState.search = { attemptId: ATTEMPT.attemptId };
  sessionStorage.setItem(`yilian.practice.sentence.${SENTENCE.id}`, JSON.stringify(SENTENCE));
  practiceApi.getAttempt.mockResolvedValue(ATTEMPT);
  const view = renderPage();
  await screen.findByText("参考译文");
  await userEvent.setup().click(screen.getByRole("button", { name: "我觉得也对" }));
  return view;
}

describe("PracticePage 申诉透明度", () => {
  beforeAll(() => {
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
    practiceApi.appealAnnotation.mockReset();
    practiceApi.disputeAnnotation.mockReset();
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

  /** [AC-7] PENDING + AI 意见：批注行展示「AI 意见：支持 · 摘要 · 等待人工终审」。 */
  it("showsAiOpinionWhenPending_ac7: 申诉回执带 AI 意见", async () => {
    practiceApi.appealAnnotation.mockResolvedValue(
      appealResponse({
        verdict: "SUPPORT",
        decisionReason: "与规范答案语义等价，可以接受",
        decidedBy: "LLM",
        decidedAt: "2026-11-06T04:01:00Z",
      }),
    );
    const view = await openAppealForm();

    await userEvent.setup().click(screen.getByRole("button", { name: "提交申诉" }));

    const note = await screen.findByText(/AI 意见：支持 · 与规范答案语义等价，可以接受/);
    expect(note).toHaveTextContent("等待人工终审");
    view.unmount();
  });

  /**
   * [AC-7] 初裁不可用（UNAVAILABLE 占位）：提示暂不可用，仍等待人工终审。
   * 夹具与真实服务端契约同形（按集成测试修复 mock 漂移）：verdict="UNAVAILABLE" +
   * 占位理由 + decidedBy/decidedAt=null，逐字段对照服务端集成测试
   * PracticeAppealAdjudicationIntegrationTest.ac4_unavailableVerdictIsExpressedInReceipt_ac4
   * 的真实回执断言，不再 mock 真实服务端不存在的响应形状。
   */
  it("showsUnavailableWhenAdjudicationSkipped_ac7: 初裁不可用文案", async () => {
    practiceApi.appealAnnotation.mockResolvedValue(
      appealResponse({
        verdict: "UNAVAILABLE",
        decisionReason: "AI 初裁暂不可用，等待人工终审",
        decidedBy: null,
        decidedAt: null,
      }),
    );
    const view = await openAppealForm();

    await userEvent.setup().click(screen.getByRole("button", { name: "提交申诉" }));

    expect(await screen.findByText("AI 初裁暂不可用 · 等待人工终审")).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-7] 无 AI 意见（四字段缺省）：保持原「申诉已记录」文案，不展示空摘要。 */
  it("keepsRecordedCopyWithoutOpinion_ac7: 无 AI 意见保持原文案", async () => {
    practiceApi.appealAnnotation.mockResolvedValue(appealResponse());
    const view = await openAppealForm();

    await userEvent.setup().click(screen.getByRole("button", { name: "提交申诉" }));

    expect(await screen.findByText("申诉已记录，等待复核；当前分数不变")).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-7] 终审 ACCEPTED：展示「申诉已通过，该写法已加入可接受答案」。 */
  it("showsAcceptedCopy_ac7: 终审通过文案", async () => {
    practiceApi.appealAnnotation.mockResolvedValue(
      appealResponse({
        status: "ACCEPTED",
        verdict: "SUPPORT",
        decisionReason: "写法与规范答案语义等价",
        decidedBy: "ADMIN",
        decidedAt: "2026-11-06T05:00:00Z",
      }),
    );
    const view = await openAppealForm();

    await userEvent.setup().click(screen.getByRole("button", { name: "提交申诉" }));

    expect(await screen.findByText("申诉已通过，该写法已加入可接受答案")).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-7] 终审 REJECTED：展示「申诉未通过」。 */
  it("showsRejectedCopy_ac7: 终审驳回文案", async () => {
    practiceApi.appealAnnotation.mockResolvedValue(
      appealResponse({
        status: "REJECTED",
        verdict: "OPPOSE",
        decisionReason: "与参考语义不符",
        decidedBy: "ADMIN",
        decidedAt: "2026-11-06T05:00:00Z",
      }),
    );
    const view = await openAppealForm();

    await userEvent.setup().click(screen.getByRole("button", { name: "提交申诉" }));

    expect(await screen.findByText("申诉未通过")).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-7] DISPUTE 语义回归：回执 ACCEPTED 仍展示「已排除：不计入错题与统计」。 */
  it("keepsDisputeExcludedCopy_ac7: 不认同文案不变", async () => {
    practiceApi.disputeAnnotation.mockResolvedValue(
      appealResponse({ kind: "DISPUTE", status: "ACCEPTED", verdict: null }),
    );
    const view = await openAppealForm();

    await userEvent.setup().click(screen.getByRole("button", { name: "不认同" }));
    await userEvent.setup().click(screen.getByRole("button", { name: "排除这条" }));

    const note = await screen.findByText("已排除：不计入错题与统计");
    expect(within(note.parentElement ?? note).queryByText(/AI 意见/)).toBeNull();
    view.unmount();
  });
});
