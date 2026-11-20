import { type QueryClient, QueryClientProvider } from "@tanstack/react-query";
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
import type {
  AttemptRequest,
  AttemptResponse,
  ModelReview,
  PracticeSentence,
  Today,
} from "@/lib/schemas/practice";

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

/** 最小 router 桩：练习页测试不感知真实路由，search 取 routerState（逐用例可注入）；href 仅为过 a11y 检查。
 * navigate 把 search 写回可变桩，让下拉切换/提交回跳的 URL 同步在桩内闭合（真实路由由 TanStack 完成）。 */
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

/** 错题池桩句：语块文案与普通桩句不相交，切池后题干变化可直接观察。 */
const ERRORS_SENTENCE: PracticeSentence = {
  ...SENTENCE,
  id: "01JSEN00000000000000000003",
  sentenceVersionId: "01JVER00000000000000000003",
  zh: "你应该注意细节。",
  ref: "You should pay attention to details.",
  level: "CET4",
  chunks: [
    { index: 0, zh: "你应该", answers: ["should"], tag: null, note: null },
    { index: 1, zh: "注意", answers: ["attention"], tag: null, note: null },
    { index: 2, zh: "细节", answers: ["details"], tag: null, note: null },
  ],
  blank: [1],
  tags: ["动词搭配"],
};

/** 错题模式提交桩：本句全对、无需改对，「下一句」立即可用。 */
const ERRORS_SUBMIT_OK: AttemptResponse = {
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0003",
  sentenceId: ERRORS_SENTENCE.id,
  sentenceVersionId: ERRORS_SENTENCE.sentenceVersionId,
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
    id: "01HGRADING0000000000000003",
    sequence: 1,
    grader: "LOCAL",
    normalizerVersion: "norm-1",
    rubricVersion: "rubric-1",
    createdAt: "2026-11-06T04:00:00Z",
  },
  review: null,
  reference: { ref: ERRORS_SENTENCE.ref, note: null },
  nextAction: { retryRequired: false, canReveal: true },
  replayed: false,
};

/**
 * 挂载练习页（页面自身用 react-query，包 QueryClient 即可）。
 * @param client 可注入的 QueryClient；缓存键级断言的用例传入同一实例以检查缓存内容。
 * @returns RTL 句柄
 */
function renderPage(client?: QueryClient) {
  return render(
    <QueryClientProvider client={client ?? createQueryClient()}>
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

  /** [AC-4] 暂时性失败后收起面板:「问一问」入口旁保留「暂不可用」角标——
   * 失败状态不随面板收起一起消失(永久不可用 40304/40400 仍整体隐藏入口,不在此列)。 */
  it("keepsUnavailableBadgeAfterPanelCollapse_ac4", async () => {
    practiceApi.askTutor.mockRejectedValue(new Error("网络中断"));
    renderPage();

    await askInPanel("为什么用 importance？");
    const panel = within(await screen.findByRole("region", { name: "题目答疑" }));
    expect(await panel.findByRole("alert")).toBeTruthy();

    // 收起面板:入口与「暂不可用」角标同时可见。
    await userEvent.setup().click(panel.getByRole("button", { name: "收起" }));
    expect(screen.queryByRole("region", { name: "题目答疑" })).toBeNull();
    expect(screen.getByRole("button", { name: "问一问" })).toBeTruthy();
    expect(screen.getByText("暂不可用")).toBeTruthy();
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

describe("PracticePage 错题集下拉与取题", () => {
  beforeAll(() => {
    // jsdom 未实现 scrollIntoView；useFocusScroll 在输入聚焦后 250ms 会调用它。
    Element.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
    routerState.search = {};
    useSessionStore.setState({ status: "anonymous" });
    practiceApi.getToday.mockResolvedValue(TODAY);
    // 错题/普通两池回不同桩句：切池后题干变化可直接观察，不依赖缓存内部状态。
    practiceApi.getNextPractice.mockImplementation((params: { errors?: boolean }) =>
      Promise.resolve({
        sentence: params.errors ? ERRORS_SENTENCE : SENTENCE,
        review: null,
        selection: params.errors ? ("DUE" as const) : ("NEW" as const),
      }),
    );
    practiceApi.getAttempt.mockResolvedValue(null);
    practiceApi.submitAttempt.mockReset();
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

  /** [AC-1] 下拉 option 与今日页 chips 同口径：文案取「 · 」前短名——会话条 236px 里
   * 放不下完整长名，且两处入口的题库叫法必须一致。 */
  it("bankDropdownOptionShowsShortName_ac1", async () => {
    contentApi.getBanks.mockResolvedValue({
      items: [{ ...BANK, name: "官方题库 · CET6 冲刺篇" }],
      page: 1,
      size: 100,
      total: 1,
      totalPages: 1,
      hasNext: false,
    } satisfies PageResponse<BankView>);
    const view = renderPage();
    await screen.findByText("环境保护");

    const select = screen.getByRole("combobox", { name: "选择题库" });
    const option = within(select).getByRole("option", { name: "官方题库" });
    expect(option).toBeTruthy();
    // 完整长名不进 option 文案。
    expect(option.textContent).not.toContain("CET6");
    view.unmount();
  });

  /** [AC-1] 下拉首项「错题集」：选中即进入错题模式——URL search 同步 errors=true 并清空
   * bankId；再选普通题库时 errors 清除、按 bankId 取题（URL search 是唯一真源）。 */
  it("bankDropdownErrorsOptionSyncsSearchAndPools_ac1", async () => {
    const client = createQueryClient();
    const view = renderPage(client);
    await screen.findByText("环境保护");

    const select = screen.getByRole("combobox", { name: "选择题库" });
    expect(within(select).getAllByRole("option")[0]).toHaveTextContent("错题集");
    expect(select).toHaveValue("7");

    // 选「错题集」：search 写入 errors=true 且无 bankId；下拉与取题请求随 search 切池。
    await userEvent.setup().selectOptions(select, "errors");
    expect(routerState.search).toEqual({ errors: true });
    await screen.findByText("注意");
    expect(screen.getByRole("combobox", { name: "选择题库" })).toHaveValue("errors");
    expect(practiceApi.getNextPractice.mock.calls.at(-1)?.[0]).toEqual({
      mode: "CLOZE",
      errors: true,
    });

    // 再选普通题库：errors 清除、按 bankId 取题；30s staleTime 内普通库键仍有缓存，不重复请求。
    const fetchCountInErrors = practiceApi.getNextPractice.mock.calls.length;
    await userEvent.setup().selectOptions(screen.getByRole("combobox", { name: "选择题库" }), "7");
    expect(routerState.search).toEqual({ bankId: "7" });
    await screen.findByText("环境保护");
    expect(screen.getByRole("combobox", { name: "选择题库" })).toHaveValue("7");
    expect(practiceApi.getNextPractice).toHaveBeenCalledTimes(fetchCountInErrors);
    expect(practiceApi.getNextPractice.mock.calls.at(-1)?.[0]).toEqual({
      mode: "CLOZE",
      errors: true,
    });
    view.unmount();
  });

  /** [AC-2] 错题模式取题/回跳/换句恒带 errors=true：首取不带 bankId（错题池不按库收窄），
   * 提交回跳保留 errors，「下一句」请求带 errors 与刚练句的排除列表。 */
  it("errorsModeCarriesErrorsAcrossFetchSubmitAndAdvance_ac2", async () => {
    routerState.search = { errors: true };
    practiceApi.submitAttempt.mockResolvedValue(ERRORS_SUBMIT_OK);
    practiceApi.getAttempt.mockResolvedValue(ERRORS_SUBMIT_OK);
    renderPage();

    // 首次取题：errors=true 且无 bankId。
    await screen.findByText("注意");
    expect(practiceApi.getNextPractice.mock.calls[0]?.[0]).toEqual({ mode: "CLOZE", errors: true });

    // 作答并提交：回跳 search 带 attemptId 与 errors=true，刷新/回跳后仍在错题池。
    await userEvent.setup().type(screen.getByLabelText("第 1 空第 1 词：注意"), "attention");
    await userEvent.setup().click(screen.getByRole("button", { name: /提交/ }));
    expect(await screen.findByText("100")).toBeTruthy();
    expect(routerState.search).toEqual({ attemptId: ERRORS_SUBMIT_OK.attemptId, errors: true });

    // 「下一句」：取题请求仍带 errors=true，并把刚练过的句排除。
    await userEvent.setup().click(await screen.findByRole("button", { name: /下一句/ }));
    await waitFor(() =>
      expect(practiceApi.getNextPractice.mock.calls.at(-1)?.[0]).toEqual({
        mode: "CLOZE",
        errors: true,
        excludeIds: [ERRORS_SENTENCE.id],
      }),
    );
  });

  /** [AC-2] 错题清完的空态：保留「错题清完了」标题与出路，不落入普通空态文案。 */
  it("errorsModeKeepsClearedEmptyState_ac2", async () => {
    routerState.search = { errors: true };
    practiceApi.getNextPractice.mockResolvedValue(null);
    renderPage();

    expect(await screen.findByText("错题清完了")).toBeTruthy();
    expect(screen.getByText(/最近做错的句子都已修复或被排除/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "去复习队列" })).toBeTruthy();
  });

  /** [AC-3] 取题 query key 携带 errors 维度：errors 收进参数对象（键尾变通标记位消灭），
   * 错题键与普通键不重合，缓存各持一份。 */
  it("nextQueryKeyCarriesErrorsDimension_ac3", async () => {
    const client = createQueryClient();
    const view = renderPage(client);
    await screen.findByText("环境保护");

    await userEvent
      .setup()
      .selectOptions(screen.getByRole("combobox", { name: "选择题库" }), "errors");
    await screen.findByText("注意");

    const nextKeys = client
      .getQueryCache()
      .getAll()
      .map((query) => query.queryKey)
      .filter((key) => key[0] === "practice" && key[1] === "next");
    expect(nextKeys).toContainEqual(["practice", "next", { mode: "CLOZE", errors: true }]);
    const overlapped = nextKeys.some((key) => {
      const params = key[2];
      return (
        typeof params === "object" && params !== null && "errors" in params && "bankId" in params
      );
    });
    expect(overlapped).toBe(false);
    view.unmount();
  });
});

/** 双空句夹具：首空判对（多词）、次空判错（单词），词框路径重打只需键入判错的空。 */
const RETRY_SENTENCE: PracticeSentence = {
  ...SENTENCE,
  id: "01JSEN00000000000000000009",
  sentenceVersionId: "01JVER00000000000000000009",
  zh: "他越来越重视这件事。",
  ref: "He pays more and more attention to this.",
  chunks: [
    { index: 0, zh: "他越来越重视", answers: ["He pays"], tag: null, note: null },
    { index: 1, zh: "越来越多", answers: ["more and more"], tag: null, note: null },
    { index: 2, zh: "重视", answers: ["attention"], tag: null, note: null },
    { index: 3, zh: "这件事", answers: ["to this"], tag: null, note: null },
  ],
  blank: [1, 2],
};

/** 双空判分桩：首空判对、次空判错，retryRequired=true 驱动「改对才过」块。 */
const RETRY_ATTEMPT: AttemptResponse = {
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0009",
  sentenceId: RETRY_SENTENCE.id,
  sentenceVersionId: RETRY_SENTENCE.sentenceVersionId,
  mode: "CLOZE",
  kind: "FIRST",
  score: 75,
  maxScore: 100,
  result: {
    schemaVersion: 1,
    score: 75,
    maxScore: 100,
    hitCount: 3,
    totalCount: 4,
    annotations: [
      {
        key: "slot:1",
        chunkIndex: 1,
        tag: "副词结构",
        expected: ["more and more"],
        received: "more and more",
        correct: true,
        message: null,
        minimalFix: null,
      },
      {
        key: "slot:2",
        chunkIndex: 2,
        tag: "搭配",
        expected: ["attention"],
        received: "attension",
        correct: false,
        message: "拼写不正确",
        minimalFix: "attention",
      },
    ],
    summary: "还差 1 处",
  },
  grading: { ...SERVER_ATTEMPT.grading },
  review: null,
  reference: { ref: RETRY_SENTENCE.ref, note: null },
  nextAction: { retryRequired: true, canReveal: true },
  replayed: false,
};

/** 重打核对成功桩：RETRY 判 100、无需再改对。 */
const RETRY_RETRY_OK: AttemptResponse = {
  ...RETRY_ATTEMPT,
  attemptId: "9e1b2c3d-0000-4000-8000-000000000001",
  kind: "RETRY",
  score: 100,
  result: {
    ...RETRY_ATTEMPT.result,
    score: 100,
    hitCount: 4,
    annotations: [],
    summary: "全部命中",
  },
  nextAction: { retryRequired: false, canReveal: true },
};

describe("PracticePage 只重做出错的空", () => {
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
      sentence: RETRY_SENTENCE,
      review: null,
      selection: "NEW",
    });
    practiceApi.getAttempt.mockResolvedValue(RETRY_ATTEMPT);
    practiceApi.submitAttempt.mockResolvedValue(RETRY_ATTEMPT);
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
      items: [RETRY_SENTENCE],
      nextSinceVersion: 0,
      hasMore: false,
    });
  });

  /** [AC-1] 会话内部分答错→重打：判对的空预填参考、判错的空留空并聚焦首错空；核对按全槽上送。 */
  it("retypePrefillsCorrectSlotsAndFocusesFirstWrong_ac1", async () => {
    renderPage();
    await screen.findByText("他越来越重视这件事。");
    const user = userEvent.setup();
    // 首空按词框逐词作答（Enter 跳下一词框），次空写错后提交。
    await user.type(screen.getByLabelText("第 1 空第 1 词：越来越多"), "more");
    await user.keyboard("{Enter}");
    await user.type(screen.getByLabelText("第 1 空第 2 词：越来越多"), "and");
    await user.keyboard("{Enter}");
    await user.type(screen.getByLabelText("第 1 空第 3 词：越来越多"), "more");
    await user.type(screen.getByLabelText("第 2 空第 1 词：重视"), "attension");
    await user.click(screen.getByRole("button", { name: /提交/ }));
    expect(await screen.findByText("75")).toBeTruthy();

    await user.click(screen.getByRole("button", { name: "重打出错的空" }));

    expect((screen.getByLabelText("第 1 空第 1 词：越来越多") as HTMLInputElement).value).toBe(
      "more",
    );
    expect((screen.getByLabelText("第 1 空第 3 词：越来越多") as HTMLInputElement).value).toBe(
      "more",
    );
    expect((screen.getByLabelText("第 2 空第 1 词：重视") as HTMLInputElement).value).toBe("");
    // 焦点落在首个错空的第一个词框。
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByLabelText("第 2 空第 1 词：重视")),
    );

    practiceApi.submitAttempt.mockClear();
    practiceApi.submitAttempt.mockResolvedValue(RETRY_RETRY_OK);
    await user.type(screen.getByLabelText("第 2 空第 1 词：重视"), "attention");
    await user.click(screen.getByRole("button", { name: "核对" }));
    expect(await screen.findByText("已修复")).toBeTruthy();

    const retryCall = practiceApi.submitAttempt.mock.calls.at(-1)?.[0] as AttemptRequest;
    expect(retryCall.kind).toBe("RETRY");
    expect(retryCall.retryOfId).toBe(RETRY_ATTEMPT.attemptId);
    expect(retryCall.answer.mode).toBe("CLOZE");
    expect(retryCall.answer.slots).toEqual([
      { chunkIndex: 1, value: "more and more" },
      { chunkIndex: 2, value: "attention" },
    ]);
  });

  /** [AC-2] 错题深链（errors=1 + lastAttemptId）回看重打：经 GET /attempts/{id} 取父批注预填。 */
  it("errorsDeepLinkPrefillsFromFetchedAttempt_ac2", async () => {
    routerState.search = { attemptId: RETRY_ATTEMPT.attemptId, errors: true };
    sessionStorage.setItem(
      `yilian.practice.sentence.${RETRY_SENTENCE.id}`,
      JSON.stringify(RETRY_SENTENCE),
    );
    practiceApi.getAttempt.mockResolvedValue(RETRY_ATTEMPT);
    renderPage();
    await screen.findByText("75");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "重打出错的空" }));
    expect((screen.getByLabelText("第 1 空第 1 词：越来越多") as HTMLInputElement).value).toBe(
      "more",
    );
    expect((screen.getByLabelText("第 2 空第 1 词：重视") as HTMLInputElement).value).toBe("");
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByLabelText("第 2 空第 1 词：重视")),
    );
    expect(practiceApi.getAttempt).toHaveBeenCalledWith(RETRY_ATTEMPT.attemptId, expect.anything());
  });

  /** [AC-2] 父 attempt 缺逐槽批注时优雅降级：词框全空、聚焦首空，不崩溃。 */
  it("retypeDegradesToAllEmptyWhenAnnotationsMissing_ac2", async () => {
    routerState.search = { attemptId: RETRY_ATTEMPT.attemptId, errors: true };
    sessionStorage.setItem(
      `yilian.practice.sentence.${RETRY_SENTENCE.id}`,
      JSON.stringify(RETRY_SENTENCE),
    );
    practiceApi.getAttempt.mockResolvedValue({
      ...RETRY_ATTEMPT,
      result: { ...RETRY_ATTEMPT.result, annotations: [] },
    });
    renderPage();
    await screen.findByText("75");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "重打出错的空" }));
    expect((screen.getByLabelText("第 1 空第 1 词：越来越多") as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText("第 2 空第 1 词：重视") as HTMLInputElement).value).toBe("");
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByLabelText("第 1 空第 1 词：越来越多")),
    );
  });

  /** [AC-3] 整句重打流程不变：整句差异被拦截，一致才按整句文本上送且不带 slots。 */
  it("freeRetypeFlowStaysWholeSentenceValidation_ac3", async () => {
    const freeAttempt: AttemptResponse = {
      ...SERVER_ATTEMPT,
      attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0008",
      mode: "FREE",
      score: 40,
      result: {
        schemaVersion: 1,
        score: 40,
        maxScore: 100,
        hitCount: 1,
        totalCount: 3,
        annotations: [
          {
            key: "free:0",
            chunkIndex: 0,
            tag: "句意",
            expected: [],
            received: "He pays more and more attention on this.",
            correct: false,
            message: "介词搭配不正确",
            minimalFix: null,
          },
        ],
        summary: "还有差异",
      },
      nextAction: { retryRequired: true, canReveal: true },
    };
    routerState.search = { attemptId: freeAttempt.attemptId };
    sessionStorage.setItem(`yilian.practice.sentence.${SENTENCE.id}`, JSON.stringify(SENTENCE));
    practiceApi.getAttempt.mockResolvedValue(freeAttempt);
    practiceApi.submitAttempt.mockResolvedValue({
      ...freeAttempt,
      attemptId: "9e1b2c3d-0000-4000-8000-000000000002",
      kind: "RETRY",
      score: 100,
      result: { ...freeAttempt.result, score: 100, annotations: [], summary: "完全一致" },
      nextAction: { retryRequired: false, canReveal: true },
    });
    renderPage();
    await screen.findByText("40");
    const user = userEvent.setup();
    const retypeBox = screen.getByLabelText("重打参考译文");
    await user.type(retypeBox, "He said something different.");
    await user.click(screen.getByRole("button", { name: "核对" }));
    expect(await screen.findByText(/还有差异，再对照一遍/)).toBeTruthy();
    expect(practiceApi.submitAttempt).not.toHaveBeenCalled();

    await user.clear(retypeBox);
    await user.type(retypeBox, SENTENCE.ref);
    await user.click(screen.getByRole("button", { name: "核对" }));
    expect(await screen.findByText("已修复")).toBeTruthy();
    const retryCall = practiceApi.submitAttempt.mock.calls.at(-1)?.[0] as AttemptRequest;
    expect(retryCall.kind).toBe("RETRY");
    expect(retryCall.retryOfId).toBe(freeAttempt.attemptId);
    expect(retryCall.answer.mode).toBe("FREE");
    expect(retryCall.answer.text).toBe(SENTENCE.ref);
    expect(retryCall.answer.slots).toBeUndefined();
  });
});

describe("PracticePage 判分后操作锁定", () => {
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
  /** [AC-2] 判分后「首字母」随题目锁定禁用置灰:不再出现可点但无反馈的死按钮;
   * 判分前提示仍可用,两级状态互不干扰。 */
  it("disablesHintButtonOnceGraded_ac2", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("环境保护");
    expect((screen.getByRole("button", { name: "首字母" }) as HTMLButtonElement).disabled).toBe(
      false,
    );
    await user.click(screen.getByRole("button", { name: "首字母" }));
    expect(screen.getByText(/首字母：/)).toBeTruthy();
    practiceApi.submitAttempt.mockResolvedValue(SERVER_ATTEMPT);
    await user.type(screen.getByLabelText("第 1 空第 1 词：环境保护"), "importance on");
    await user.click(screen.getByRole("button", { name: /提交/ }));
    expect(await screen.findByText("61")).toBeTruthy();
    expect((screen.getByRole("button", { name: "首字母" }) as HTMLButtonElement).disabled).toBe(
      true,
    );
    await user.click(screen.getByRole("button", { name: "首字母" }));
    expect(screen.queryByText(/词数：/)).toBeNull();
  });
});
