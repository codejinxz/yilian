import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TodayPage } from "@/features/today/TodayPage";
import { createQueryClient } from "@/lib/query";
import type { PageResponse } from "@/lib/schemas/common";
import type { BankView } from "@/lib/schemas/content";
import type { Today } from "@/lib/schemas/practice";

/**
 * 今日启动台 CTA 语义与题库 chips 折叠。
 *
 * 主按钮改为「开始今日 N 题」（N = 每日目标 − 已练，来自既有 today 聚合），
 * 清完当日目标兜底「今日已清完，可自由练习」；题库 chips（内部/测试库名）默认折叠进
 * 「更多题库」，错题 chip 常驻；0 句的库不再占位。
 */

const practiceApi = vi.hoisted(() => ({
  getToday: vi.fn(),
  getWrongQuestions: vi.fn(),
}));

vi.mock("@/lib/api/practice", () => practiceApi);

const analyticsApi = vi.hoisted(() => ({
  getAccuracy: vi.fn(),
}));

vi.mock("@/lib/api/analytics", () => analyticsApi);

const contentApi = vi.hoisted(() => ({
  getBanks: vi.fn(),
  getBankSentences: vi.fn(),
  getPickerSentences: vi.fn(),
}));

vi.mock("@/lib/api/content", () => contentApi);

const engine = vi.hoisted(() => ({
  ensureCachedSentences: vi.fn(),
}));

vi.mock("@/lib/local-engine", () => engine);

/** 最小 router 桩：今日页只用 Link 与 useNavigate。 */
vi.mock("@tanstack/react-router", () => ({
  Link: (props: { to?: string; search?: unknown; className?: string; children?: ReactNode }) => (
    <a href="#stub" className={props.className}>
      {props.children}
    </a>
  ),
  useNavigate: () => async () => {},
}));

/** 今日聚合桩：目标 3、已练 1 → 待练 2。 */
const TODAY: Today = {
  date: "2026-11-06",
  timezone: "Asia/Shanghai",
  dueCount: 5,
  doneCount: 1,
  goal: 3,
  correctRate: null,
  streakDays: 2,
};

/** 已练达标（3/3）：主按钮进入兜底文案态。 */
const TODAY_CLEARED: Today = { ...TODAY, doneCount: 3 };

const OFFICIAL_BANK: BankView = {
  id: "1",
  ownerId: null,
  name: "官方题库 · TEM8 翻译",
  description: null,
  visibility: "PUBLIC",
  status: "PUBLISHED",
  version: 1,
  sentenceCount: 8,
};

/** 0 句的回收库：不可练，不应出现在 chips 里。 */
const EMPTY_BANK: BankView = {
  id: "2",
  ownerId: null,
  name: "回收库 · 已清空",
  description: null,
  visibility: "PUBLIC",
  status: "PUBLISHED",
  version: 1,
  sentenceCount: 0,
};

/** 页面渲染壳：今日页自身用 react-query。 */
function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <TodayPage />
    </QueryClientProvider>,
  );
}

/** analytics extras 的空应答：续练行与周点阵隐藏，不影响 CTA 与 chips 断言。 */
function mockExtrasEmpty() {
  analyticsApi.getAccuracy.mockResolvedValue({
    from: "2026-10-24",
    to: "2026-11-06",
    totalAttempts: 0,
    correctRate: null,
    averageScore: null,
    byMode: [],
    daily14: [],
    recent: [],
  });
  engine.ensureCachedSentences.mockResolvedValue([]);
}

describe("TodayPage 今日 CTA 与题库 chips（027 AC-7）", () => {
  beforeEach(() => {
    window.localStorage.clear();
    practiceApi.getToday.mockReset();
    practiceApi.getWrongQuestions.mockReset();
    contentApi.getBanks.mockReset();
    practiceApi.getWrongQuestions.mockResolvedValue({
      items: [],
      page: 1,
      size: 1,
      total: 4,
      totalPages: 4,
      hasNext: false,
    } satisfies PageResponse<never>);
    contentApi.getBanks.mockResolvedValue({
      items: [OFFICIAL_BANK, EMPTY_BANK],
      page: 1,
      size: 100,
      total: 2,
      totalPages: 1,
      hasNext: false,
    } satisfies PageResponse<BankView>);
    mockExtrasEmpty();
  });

  /** 主按钮语义：N = 每日目标 − 已练，不再出现「做 10 句热身」的旧口径。 */
  it("todayCtaUsesDailyRemaining_ac7", async () => {
    practiceApi.getToday.mockResolvedValue(TODAY);
    renderPage();

    const cta = await screen.findByRole("link", { name: /开始今日 2 题/ });
    expect(cta).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /热身/ })).toBeNull();
    // 旧口径「开始 N 句」（按到期数）不再出现。
    expect(screen.queryByText("开始 5 句")).toBeNull();
  });

  /** 清完当日目标：兜底文案，按钮仍可进入自由练习。 */
  it("todayCtaFallbackWhenQuotaCleared_ac7", async () => {
    practiceApi.getToday.mockResolvedValue(TODAY_CLEARED);
    renderPage();

    expect(await screen.findByRole("link", { name: /今日已清完，可自由练习/ })).toBeInTheDocument();
  });

  /** 题库 chips 默认折叠；展开后 0 句库不渲染；错题 chip 常驻。 */
  it("bankChipsCollapsedByDefault_ac7", async () => {
    practiceApi.getToday.mockResolvedValue(TODAY);
    renderPage();

    // 错题 chip 常驻；题库 chip 默认不可见，折叠入口给出可展开的题库数（0 句库不计）。
    expect(await screen.findByRole("link", { name: /错题 4/ })).toBeInTheDocument();
    const toggle = screen.getByRole("button", { name: "更多题库 1" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText(/官方题库/)).toBeNull();
    expect(screen.queryByText(/回收库/)).toBeNull();

    await userEvent.setup().click(toggle);
    expect(screen.getByRole("button", { name: "收起题库" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByText(/官方题库/)).toBeInTheDocument();
    // 0 句的回收库即使展开也不占位。
    expect(screen.queryByText(/回收库/)).toBeNull();
  });
});
