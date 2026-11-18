import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
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
function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
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
