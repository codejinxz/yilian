import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { StatsPage } from "@/features/stats/StatsPage";
import type { LocalStatsSnapshot } from "@/lib/local-engine";
import { createQueryClient } from "@/lib/query";

/**
 * StatsPage 走游客分支验证「错误集中在」的相对比例(AC-5):mock 会话探针与引擎快照,
 * 三源里引擎快照最小可控;mock/登录态分流分支不在此覆盖。
 */

const apiStubs = vi.hoisted(() => ({
  isGuestSession: vi.fn(() => true),
  isMockEnabled: vi.fn(() => false),
}));

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  isGuestSession: apiStubs.isGuestSession,
}));
vi.mock("@/lib/mock-flag", () => ({ isMockEnabled: apiStubs.isMockEnabled }));

/** 引擎快照桩:统计页只消费本批涉及的 errorTags,其余字段给合法最小值。 */
const engineApi = vi.hoisted(() => ({ getStatsSnapshot: vi.fn(), ensureCachedSentences: vi.fn() }));
vi.mock("@/lib/local-engine", () => engineApi);
const SNAPSHOT: LocalStatsSnapshot = {
  streakDays: 3,
  totalAttempts: 10,
  averageScore: 72.5,
  daily14: Array.from({ length: 14 }, () => 0),
  // 次条与第三条数量相同(各 2):回归 D16 的「同数满宽」——应各占一半而非全部满宽。
  errorTags: [
    { tag: "时态", count: 4 },
    { tag: "冠词", count: 2 },
    { tag: "介词", count: 2 },
  ],
  recent: [],
  correctRate: 0.6,
  byMode: [],
};

function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <StatsPage />
    </QueryClientProvider>,
  );
}
describe("StatsPage 错误集中在(S8 相对比例)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    engineApi.getStatsSnapshot.mockResolvedValue(SNAPSHOT);
  });

  /** [AC-5] 各条按相对最大值缩放:右侧数字带百分比且与条宽一致,同数量条不再同数满宽。 */
  it("scalesErrorBarsRelativelyWithPercent_ac5", async () => {
    renderPage();
    await screen.findByText("时态");

    expect(screen.getByText("4 · 100%")).toBeTruthy();
    expect(screen.getAllByText("2 · 50%")).toHaveLength(2);
    const barFor = (tag: string) => screen.getByText(tag).nextElementSibling as HTMLElement;
    expect(barFor("时态").firstElementChild).toHaveStyle({ width: "100%" });
    expect(barFor("冠词").firstElementChild).toHaveStyle({ width: "50%" });
    expect(barFor("介词").firstElementChild).toHaveStyle({ width: "50%" });
  });
});
