import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { useSessionStore } from "@/features/auth/session";
import { PracticePage } from "@/features/practice/PracticePage";
import { ApiError } from "@/lib/api-error";
import { createQueryClient } from "@/lib/query";
import type { PageResponse } from "@/lib/schemas/common";
import type { BankView } from "@/lib/schemas/content";
import type { AttemptResponse, ModelReview, PracticeSentence, Today } from "@/lib/schemas/practice";

/**
 * PracticePage 的答疑入口会话记忆：只覆盖「问一问」入口的显隐口径，
 * 答题/判分主链路由后端切片测试与既有 E2E 覆盖，这里不重复。
 *
 * mock 策略：router 用最小桩（页面只用 Link/useNavigate/useSearch），API 在模块边界整体替换
 * （与 AskPanel / sentence-picker 用例同构）；todayQueryOptions 的 queryFn 落在同一 mock 上。
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

/** @/lib/api/content 的桩；getPickerSentences 供选题面板模块导入不炸（用例不打开面板）。 */
const contentApi = vi.hoisted(() => ({
  getBanks: vi.fn(),
  getBankSentences: vi.fn(),
  getPickerSentences: vi.fn(),
}));

vi.mock("@/lib/api/content", () => contentApi);

/** 路由 search 的可变桩：缺省空（新题流），回看用例注入 attemptId。 */
const routerState = vi.hoisted(() => ({ search: {} as Record<string, unknown> }));

/** 最小 router 桩：练习页测试不感知真实路由，search 取 routerState（逐用例可注入）；href 仅为过 a11y 检查。 */
vi.mock("@tanstack/react-router", () => ({
  Link: (props: { to?: string; search?: unknown; className?: string; children?: ReactNode }) => (
    <a href="#stub" className={props.className}>
      {props.children}
    </a>
  ),
  useNavigate: () => async () => {},
  useSearch: () => routerState.search,
}));

/** 练习页取题桩句：一空填空句，字段只要页面消费到的最小集。 */
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

/** 今日启动台桩数据：页面只消费 dueCount/goal。 */
const TODAY: Today = {
  date: "2026-11-05",
  timezone: "Asia/Shanghai",
  dueCount: 2,
  doneCount: 0,
  goal: 10,
  correctRate: null,
  streakDays: 3,
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

/** 打开答疑面板并提交一个问题。 */
async function askInPanel(question: string) {
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "问一问" }));
  // AskPanel 是带 aria-label 的 section（role=region）；页面还有挖空输入框，
  // 必须圈定在面板内找 textarea 与提交键。
  const panel = within(screen.getByRole("region", { name: "题目答疑" }));
  await user.type(panel.getByRole("textbox"), question);
  await user.click(panel.getByRole("button", { name: "提问" }));
}

describe("PracticePage 答疑入口显隐（三态 + 会话记忆）", () => {
  beforeAll(() => {
    // jsdom 未实现 scrollIntoView；useFocusScroll 在输入聚焦后 250ms 会调用它，
    // 不打桩会在测试尾部抛出未处理错误。
    Element.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
    practiceApi.getToday.mockResolvedValue(TODAY);
    practiceApi.getNextPractice.mockResolvedValue({
      sentence: SENTENCE,
      review: null,
      selection: "NEW",
    });
    practiceApi.getAttempt.mockResolvedValue(null);
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

  /** 答疑三态口径：缺省乐观可见——未标记不可用时「问一问」入口在批注栏渲染。 */
  it("showsAskEntryByDefaultWhenNotMarked", async () => {
    renderPage();

    expect(await screen.findByRole("button", { name: "问一问" })).toBeTruthy();
  });

  /**
   * 40400 收敛链路：定向文案先展示、输入保留，约 1.5s 后入口整体收起，
   * 且隐藏状态写入会话记忆——卸载重挂（重进练习页）后入口不再重现，切断循环困惑。
   */
  it("keepsEntryHiddenAcrossRemountsAfterUnavailable", async () => {
    practiceApi.askTutor.mockRejectedValue(new ApiError(404, 40400, "请求的资源不存在"));
    const view = renderPage();

    await askInPanel("为什么用 importance？");

    // 面板内先看到原因，输入还在（AskPanel 侧行为，这里验证端到端接线）。
    const panel = within(await screen.findByRole("region", { name: "题目答疑" }));
    expect(await panel.findByRole("alert")).toHaveTextContent("答疑功能未配置");
    expect((panel.getByRole("textbox") as HTMLTextAreaElement).value).toBe("为什么用 importance？");
    // 停留约 1.5s 后经 onUnavailable 收起：面板区域消失 = askAvailable 已翻 false，
    // 入口整体消失（面板开着时入口按钮本就不渲染，等区域消失才是收起信号）。
    await waitFor(() => expect(screen.queryByRole("region", { name: "题目答疑" })).toBeNull(), {
      timeout: 3000,
    });
    expect(screen.queryByRole("button", { name: "问一问" })).toBeNull();
    // 会话记忆已落 sessionStorage。
    expect(sessionStorage.getItem("yilian.practice.askUnavailable")).toBe("1");

    // 重进练习页（卸载重挂）：会话内记住，入口不再重现——「一问就消失、重进又出现」到此为止。
    // 题干由 ChinesePrompt 按语块拆分渲染，等语块 span 出现即题目已加载。
    view.unmount();
    renderPage();
    await screen.findByText("环境保护");
    expect(screen.queryByRole("button", { name: "问一问" })).toBeNull();
    expect(screen.queryByRole("region", { name: "题目答疑" })).toBeNull();
  });
});

/** 服务端已判分 attempt 桩：grading.id 为服务端 ULID、首判 61 分、一条错误批注。 */
const SERVER_ATTEMPT: AttemptResponse = {
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0001",
  sentenceId: SENTENCE.id,
  sentenceVersionId: SENTENCE.sentenceVersionId,
  mode: "CLOZE",
  kind: "FIRST",
  score: 61,
  maxScore: 100,
  result: {
    schemaVersion: 1,
    score: 61,
    maxScore: 100,
    hitCount: 1,
    totalCount: 2,
    annotations: [
      {
        key: "slot:1",
        chunkIndex: 1,
        tag: "搭配",
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
    id: "01HGRADING0000000000000000",
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

/** 与 SERVER_ATTEMPT 同形但判分行是本地占位（游客/离线引擎提交）：复核入口必须隐藏。 */
function localAttemptFixture(): AttemptResponse {
  return {
    ...SERVER_ATTEMPT,
    grading: { ...SERVER_ATTEMPT.grading, id: "local" },
  };
}

/** 服务端复核结果桩：模型给 88 分。 */
const REVIEW: ModelReview = {
  score: 88,
  reason: "整体可懂，介词搭配需要调整",
  modelSnapshot: "stub:glm-4.7",
  latencyMs: 640,
  createdAt: "2026-11-06T04:30:00Z",
  sequence: 2,
  originalScore: 61,
};

/**
 * 挂载回看场景的练习页：路由带 attemptId、题干快照就位、getAttempt 回服务端判分。
 * @param attempt getAttempt 返回的判分结果
 * @returns RTL 句柄（多实例用例需要手动卸载）
 */
async function renderAttemptView(attempt: AttemptResponse) {
  routerState.search = { attemptId: attempt.attemptId };
  sessionStorage.setItem(`yilian.practice.sentence.${SENTENCE.id}`, JSON.stringify(SENTENCE));
  practiceApi.getAttempt.mockResolvedValue(attempt);
  const view = renderPage();
  // 等判分视图渲染完成（展示分出现即结果区已就位）。
  await screen.findByText("61");
  return view;
}

describe("PracticePage 模型复核（API §6.13）", () => {
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

  /** [AC-8] 门禁：匿名态或本地占位判分（游客/离线引擎）都不显示复核入口。 */
  it("hidesReviewEntryForAnonymousAndLocalAttempts_ac8", async () => {
    // 匿名 + 服务端判分：不显示。
    const anonymousView = await renderAttemptView(SERVER_ATTEMPT);
    expect(screen.queryByRole("button", { name: "让模型复核" })).toBeNull();
    anonymousView.unmount();

    // 登录 + 本地占位判分：仍不显示（服务端没有这条记录，复核无从发起）。
    useSessionStore.setState({ status: "authenticated" });
    const localView = await renderAttemptView(localAttemptFixture());
    expect(screen.queryByRole("button", { name: "让模型复核" })).toBeNull();
    expect(practiceApi.reviewAttempt).not.toHaveBeenCalled();
    localView.unmount();
  });

  /** [AC-8] 成功：登录态 + 服务端判分可发起；成功后展示新分+理由+「复核不影响复习计划」注记，并刷新 attempt。 */
  it("showsReviewedScoreReasonAndScheduleNoteOnSuccess_ac8", async () => {
    useSessionStore.setState({ status: "authenticated" });
    await renderAttemptView(SERVER_ATTEMPT);
    practiceApi.reviewAttempt.mockResolvedValue(REVIEW);

    await userEvent.setup().click(await screen.findByRole("button", { name: "让模型复核" }));

    // 新分与理由：展示分换为复核分，理由进批改文案。
    expect(await screen.findByText("88")).toBeTruthy();
    expect(screen.getByText(/复核 88 分/).textContent).toContain("原判 61 分");
    expect(screen.getByText(/复核 88 分/).textContent).toContain("stub:glm-4.7");
    expect(screen.getByText("整体可懂，介词搭配需要调整")).toBeTruthy();
    // 「复核不影响复习计划」注记常驻。
    expect(screen.getByText(/复核不影响复习计划/)).toBeTruthy();
    // 入口收起：复核一人一次，重复点击走幂等回读而非再调模型。
    expect(screen.queryByRole("button", { name: "让模型复核" })).toBeNull();
    // attempt 查询已刷新（回看按最新 sequence 取复核分）。
    await waitFor(() => expect(practiceApi.getAttempt.mock.calls.length).toBeGreaterThan(1));
    // 「演示占位」假文案不再出现。
    expect(screen.queryByText(/演示占位/)).toBeNull();
  });

  /** [AC-8] 失败：toast 服务端文案，展示分保留首判分，不出现复核注记。 */
  it("keepsOriginalScoreAndToastsOnFailure_ac8", async () => {
    useSessionStore.setState({ status: "authenticated" });
    await renderAttemptView(SERVER_ATTEMPT);
    practiceApi.reviewAttempt.mockRejectedValue(
      new ApiError(500, 50000, "模型复核暂时不可用，请稍后再试"),
    );

    await userEvent.setup().click(await screen.findByRole("button", { name: "让模型复核" }));

    expect(await screen.findByText("模型复核暂时不可用，请稍后再试")).toBeTruthy();
    // 原判分保留：展示分仍是首判 61 分，没有复核分与注记。
    expect(screen.getByText("61")).toBeTruthy();
    expect(screen.queryByText(/复核不影响复习计划/)).toBeNull();
    expect(screen.queryByText(/演示占位/)).toBeNull();
  });

  /** [AC-5] 预算用尽：复核走 50000 降级（文案带预算原因），toast 如实透出，原判分保留。 */
  it("toastsBudgetDegradeMessageWhenBudgetExhausted_ac5", async () => {
    useSessionStore.setState({ status: "authenticated" });
    await renderAttemptView(SERVER_ATTEMPT);
    practiceApi.reviewAttempt.mockRejectedValue(
      new ApiError(
        500,
        50000,
        "模型复核暂时不可用：本月模型预算已用尽，请下月再试或联系管理员调整预算",
      ),
    );

    await userEvent.setup().click(await screen.findByRole("button", { name: "让模型复核" }));

    // 降级文案带预算原因：点明「预算已用尽」与出路，而非笼统的不可用。
    expect(
      await screen.findByText(
        "模型复核暂时不可用：本月模型预算已用尽，请下月再试或联系管理员调整预算",
      ),
    ).toBeTruthy();
    // 原判分保留，无复核注记。
    expect(screen.getByText("61")).toBeTruthy();
    expect(screen.queryByText(/复核不影响复习计划/)).toBeNull();
  });
});
