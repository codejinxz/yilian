import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ReviewPage } from "@/features/review/ReviewPage";
import { createQueryClient } from "@/lib/query";

/**
 * ReviewPage 冷启动会话恢复竞态：
 * me 未确认前 isGuestSession 暂按游客语义，本地引擎瞬时回空队列会让
 * 「这里还是空的」空态与全零计数先落屏、会话确认后再跳真实数据。
 * mock 策略：router 用最小桩；@/lib/api/practice 与 @/lib/local-engine 在模块边界整体替换；
 * @/lib/api 只替换 isGuestSession 与 api.me——me 的 pending/resolved/rejected 三态即会话恢复进度。
 */

/** @/lib/api 的桩：isGuestSession 可控，api.me 按用例模拟会话恢复进度。 */
const apiStubs = vi.hoisted(() => ({
  isGuestSession: vi.fn(() => false),
  me: vi.fn<() => Promise<unknown>>(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    isGuestSession: apiStubs.isGuestSession,
    api: { ...actual.api, me: apiStubs.me },
  };
});

/** @/lib/api/practice 的桩：复习列表 / 降载 / 顺延三个端点。 */
const practiceApi = vi.hoisted(() => ({
  getReviews: vi.fn(),
  getReviewOverload: vi.fn(),
  postponeReviews: vi.fn(),
}));

vi.mock("@/lib/api/practice", () => practiceApi);

/** @/lib/local-engine 的桩：游客分支的引擎队列、降载与题库缓存。 */
const engineApi = vi.hoisted(() => ({
  getReviewQueue: vi.fn(),
  getOverloadState: vi.fn(),
  postponeOverdue: vi.fn(),
  ensureCachedSentences: vi.fn(),
  getCachedSentences: vi.fn(),
}));

vi.mock("@/lib/local-engine", () => engineApi);

/** 最小 router 桩：复习页只用 Link 与 useSearch（search 缺省按「全部」档）。 */
vi.mock("@tanstack/react-router", () => ({
  Link: (props: { to?: string; search?: unknown; className?: string; children?: ReactNode }) => (
    <a href="#stub" className={props.className}>
      {props.children}
    </a>
  ),
  useSearch: () => ({}),
}));

/** 空引擎队列：游客本地无复习数据时的形态（getReviewQueue 返回值的最小集）。 */
const EMPTY_QUEUE = { rows: [], summary: { due: 0, learning: 0, mastered: 0 } };

/** 空契约分页（PageResponse 形状）。 */
const EMPTY_PAGE = { items: [], page: 1, size: 20, total: 0, totalPages: 1, hasNext: false };

/** 一条学习中已到期的复习卡：页面消费字段的最小合法集（reviewSchema）。 */
const REVIEW_CARD = {
  id: "01JREV00000000000000000001",
  state: "LEARNING",
  fsrsState: "REVIEW",
  dueAt: "2026-11-06T00:00:00Z",
  stability: 1.2,
  difficulty: 4.6,
  reps: 2,
  lapses: 1,
  scheduledDays: 1,
  sentenceId: "01JSEN00000000000000000001",
  zh: "他越来越重视环境保护。",
  mode: "CLOZE",
};

/** 契约分页一页一条（getReviews 返回值；汇总查询按 size=1 复用同一桩）。 */
const PAGE_WITH_ONE = {
  items: [REVIEW_CARD],
  page: 1,
  size: 20,
  total: 1,
  totalPages: 1,
  hasNext: false,
};

/** 空态完整文案（ReviewPage 空队列分支）。 */
const EMPTY_TEXT = "这里还是空的。去练几句，答错的会自动进来。";

/**
 * 挂载复习页（页面自身用 react-query，包 QueryClient 即可）。
 * @returns RTL 句柄
 */
function renderPage(client = createQueryClient()) {
  return render(
    <QueryClientProvider client={client}>
      <ReviewPage />
    </QueryClientProvider>,
  );
}

describe("ReviewPage 冷启动会话恢复竞态", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("会话恢复中（me 未确认）渲染加载态，不落「这里还是空的」空态与全零计数", async () => {
    apiStubs.isGuestSession.mockReturnValue(false);
    apiStubs.me.mockReturnValue(new Promise(() => {}));
    practiceApi.getReviews.mockReturnValue(new Promise(() => {}));
    practiceApi.getReviewOverload.mockResolvedValue({
      overload: false,
      dueCount: 0,
      pushLimit: 5,
    });
    engineApi.ensureCachedSentences.mockResolvedValue([]);

    renderPage();

    // 加载态在位（LoadingBlock 的骨架块），空态文案与三档 0 计数都不出现。
    expect(await screen.findByRole("status")).toBeDefined();
    expect(screen.queryByText(EMPTY_TEXT)).toBeNull();
    expect(screen.queryByText("0")).toBeNull();
    // 三档数字以 … 占位（3 个 <b>），不渲染引擎回包的全零。
    expect(screen.getAllByText("…")).toHaveLength(3);
    // 会话未确认期间不触发本地引擎队列（登录语义的拉取全部走契约桩）。
    expect(engineApi.getReviewQueue).not.toHaveBeenCalled();
    expect(practiceApi.getReviews).toHaveBeenCalled();
  });

  it("会话确认后契约数据正常渲染，门控不阻塞真实数据", async () => {
    apiStubs.isGuestSession.mockReturnValue(false);
    apiStubs.me.mockResolvedValue({ id: "1", username: "admin", role: "ADMIN" });
    practiceApi.getReviews.mockResolvedValue(PAGE_WITH_ONE);
    practiceApi.getReviewOverload.mockResolvedValue({ overload: false, dueCount: 1, pushLimit: 5 });
    engineApi.ensureCachedSentences.mockResolvedValue([]);

    renderPage();

    await screen.findByText("他越来越重视环境保护。");
    expect(screen.queryByText(EMPTY_TEXT)).toBeNull();
    // 三档计数为契约汇总的真实值（非 … 占位、非全零）。
    expect(screen.queryAllByText("…")).toHaveLength(0);
  });

  it("游客语义（会话确认失败）下空队列仍渲染空态，门控不误伤真实游客", async () => {
    apiStubs.isGuestSession.mockReturnValue(true);
    apiStubs.me.mockRejectedValue(new Error("401 未登录"));
    engineApi.getReviewQueue.mockResolvedValue(EMPTY_QUEUE);
    engineApi.getOverloadState.mockResolvedValue({ active: false, dueCount: 0 });
    engineApi.getCachedSentences.mockResolvedValue([]);

    renderPage();

    await screen.findByText(EMPTY_TEXT);
    expect(screen.getAllByText("0")).toHaveLength(3);
    expect(engineApi.getReviewQueue).toHaveBeenCalled();
  });

  it("登录态契约返回空目录时同样渲染空态（加载门控仅在恢复期生效）", async () => {
    apiStubs.isGuestSession.mockReturnValue(false);
    apiStubs.me.mockResolvedValue({ id: "1", username: "admin", role: "ADMIN" });
    practiceApi.getReviews.mockResolvedValue(EMPTY_PAGE);
    practiceApi.getReviewOverload.mockResolvedValue({ overload: false, dueCount: 0, pushLimit: 5 });
    engineApi.ensureCachedSentences.mockResolvedValue([]);

    renderPage();

    await screen.findByText(EMPTY_TEXT);
  });
});

/**
 * 构造本地时区正午的 ISO 时间：正午远离日界与夏令时切换边沿，桶位断言与运行环境时区无关。
 * @param daysAhead 相对今天的天数
 * @returns ISO 时间
 */
function localNoonIso(daysAhead: number): string {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() + daysAhead, 12).toISOString();
}

/** 日历页与列表页共用的一张学习中复习卡（页面消费字段的最小集）。 */
function reviewCard(id: string, zh: string, dueAt: string) {
  return {
    id,
    state: "LEARNING",
    fsrsState: "REVIEW",
    dueAt,
    stability: 1.2,
    difficulty: 4.6,
    reps: 2,
    lapses: 1,
    scheduledDays: 1,
    sentenceId: id,
    zh,
    mode: "CLOZE",
  };
}

/**
 * 按参数分流 getReviews 桩：日历（学习中首页 dueAt 升序）、三档汇总（size=1）与列表首页三个去向。
 * 列表首页固定空目录，日历计数与列表渲染互不干扰。
 * @param calendarItems 日历页条目
 * @param options hasNext 与 total（缺省按未截断、条目数计）
 */
function stubContractPages(
  calendarItems: ReturnType<typeof reviewCard>[],
  options: { hasNext?: boolean; total?: number } = {},
) {
  practiceApi.getReviews.mockImplementation(
    (params: { size?: number; state?: string; due?: boolean }) => {
      if (params.size === 20 && params.state === "LEARNING" && !params.due) {
        return Promise.resolve({
          items: calendarItems,
          page: 1,
          size: 20,
          total: options.total ?? calendarItems.length,
          totalPages: 1,
          hasNext: options.hasNext ?? false,
        });
      }
      if (params.size === 1) {
        // 三档汇总固定 3/4/5，与日历桶计数互不重合，避免文本断言串位。
        return Promise.resolve({
          items: [],
          page: 1,
          size: 1,
          total: params.due ? 3 : params.state === "MASTERED" ? 5 : 4,
          totalPages: 1,
          hasNext: false,
        });
      }
      return Promise.resolve({
        items: [],
        page: 1,
        size: 20,
        total: 0,
        totalPages: 1,
        hasNext: false,
      });
    },
  );
}

/** 登录态桩的公共部分：会话已确认、题库缓存为空、无降载。 */
function stubLoggedInUser() {
  apiStubs.isGuestSession.mockReturnValue(false);
  apiStubs.me.mockResolvedValue({ id: "1", username: "admin", role: "ADMIN" });
  engineApi.ensureCachedSentences.mockResolvedValue([]);
  practiceApi.getReviewOverload.mockResolvedValue({ overload: false, dueCount: 0, pushLimit: 5 });
}

describe("ReviewPage 7 日到期日历", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  /** [AC-1] 未来 7 天七个日界桶：逾期与今天到期并入今天桶，今日列以 aria-current="date" 可辨。 */
  it("登录态渲染 7 桶日历：到期卡按本地日界分桶、今日列可辨", async () => {
    stubLoggedInUser();
    stubContractPages([
      reviewCard("c1", "已到期卡", "2026-11-06T00:00:00Z"),
      reviewCard("c2", "两天后卡", localNoonIso(2)),
      reviewCard("c3", "另一张两天后卡", localNoonIso(2)),
    ]);
    renderPage();

    const region = await screen.findByRole("region", { name: "未来 7 天到期" });
    const cells = within(region).getAllByRole("listitem");
    expect(cells).toHaveLength(7);
    expect(within(cells[0]).getByText("今天")).toBeTruthy();
    expect(within(cells[0]).getByText("1")).toBeTruthy();
    expect(within(cells[2]).getByText("2")).toBeTruthy();
    expect(within(cells[1]).getByText("0")).toBeTruthy();
    expect(cells[0]).toHaveAttribute("aria-current", "date");
    expect(cells[1]).not.toHaveAttribute("aria-current");
  });

  /** [AC-2] 无到期卡：7 桶全 0 照常渲染，不给假分布也不出现上限提示。 */
  it("无到期卡时 7 桶全 0 不破相，也不出现统计上限提示", async () => {
    stubLoggedInUser();
    stubContractPages([]);
    renderPage();

    const region = await screen.findByRole("region", { name: "未来 7 天到期" });
    expect(within(region).getAllByText("0")).toHaveLength(7);
    expect(within(region).getByText("今天")).toBeTruthy();
    expect(screen.queryByText(/只统计最近取回的/)).toBeNull();
  });

  /** [AC-2] 到期超过单页：只按已取页聚合，注明统计上限，不假装全量。 */
  it("到期超过单页时按已取页统计并如实标注上限", async () => {
    stubLoggedInUser();
    const items = Array.from({ length: 20 }, (_, index) =>
      reviewCard(`c${index}`, `到期句 ${index}`, "2026-11-06T00:00:00Z"),
    );
    stubContractPages(items, { hasNext: true, total: 27 });
    renderPage();

    const region = await screen.findByRole("region", { name: "未来 7 天到期" });
    const cells = within(region).getAllByRole("listitem");
    expect(within(cells[0]).getByText("20")).toBeTruthy();
    expect(screen.getByText("到期较多，日历只统计最近取回的 20 句。")).toBeTruthy();
  });

  /** [AC-1] 日历查询与列表/汇总共用全局 30s staleTime：口径内重挂载全部命中缓存，不重复请求。 */
  it("日历查询进缓存：30s staleTime 口径内重挂载不重复请求", async () => {
    stubLoggedInUser();
    stubContractPages([reviewCard("c1", "已到期卡", "2026-11-06T00:00:00Z")]);
    const client = createQueryClient();
    const first = renderPage(client);
    await screen.findByRole("region", { name: "未来 7 天到期" });
    // 等三档汇总最后落屏，全部查询收口后再计数，避免在途请求造成抖动。
    await screen.findByText("4");
    const callsAfterFirst = practiceApi.getReviews.mock.calls.length;
    expect(callsAfterFirst).toBeGreaterThan(0);
    first.unmount();

    renderPage(client);
    await screen.findByRole("region", { name: "未来 7 天到期" });
    expect(practiceApi.getReviews.mock.calls.length).toBe(callsAfterFirst);
  });
});
