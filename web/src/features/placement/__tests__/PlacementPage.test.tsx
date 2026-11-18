import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PlacementPage } from "@/features/placement/PlacementPage";
import { createQueryClient } from "@/lib/query";
import type { PracticeSentence } from "@/lib/schemas/practice";

/**
 * 定级提交后的「已记录」微反馈。
 *
 * 定级是全程唯一「答了没回音」的地方：提交后页面直接跳下一题，
 * 三题连答像答案消失。本组用例验证提交 → 已记录确认（约 0.6s）→ 进下一题/结果屏的节奏，
 * 以及微反馈期间按钮禁用（防连点造成双进题）。
 *
 * mock 策略：local-engine 在模块边界整体替换（定级题来源与状态落盘），router 用最小桩。
 */

const engine = vi.hoisted(() => ({
  getPlacementSentences: vi.fn(),
  savePlacementState: vi.fn(),
  setDailyGoal: vi.fn(),
}));

vi.mock("@/lib/local-engine", () => engine);

const navigateStub = vi.hoisted(() => vi.fn());

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => navigateStub,
}));

/** 定级句两道：题目只需 zh/ref/tags（判分用与练习页相同的归一化比对）。 */
const PLACEMENT_SENTENCES: PracticeSentence[] = [
  {
    id: "01JPLC00000000000000000001",
    version: 1,
    sentenceVersionId: "01JPLV00000000000000000001",
    questionVersion: 1,
    status: "PUBLISHED",
    deleted: false,
    zh: "他越来越重视环境保护。",
    ref: "He attaches more and more importance to environmental protection.",
    note: null,
    level: null,
    chunks: [],
    blank: [],
    tags: ["动词搭配"],
  },
  {
    id: "01JPLC00000000000000000002",
    version: 1,
    sentenceVersionId: "01JPLV00000000000000000002",
    questionVersion: 1,
    status: "PUBLISHED",
    deleted: false,
    zh: "我们应该充分利用时间。",
    ref: "We should make full use of the time.",
    note: null,
    level: null,
    chunks: [],
    blank: [],
    tags: ["动词搭配"],
  },
];

function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <PlacementPage />
    </QueryClientProvider>,
  );
}

describe("PlacementPage 已记录微反馈（AC-6）", () => {
  beforeEach(() => {
    window.localStorage.clear();
    engine.getPlacementSentences.mockReset();
    engine.getPlacementSentences.mockResolvedValue(PLACEMENT_SENTENCES);
    engine.savePlacementState.mockReset();
    engine.setDailyGoal.mockReset();
    navigateStub.mockReset();
  });

  /** 提交后先给「已记录」确认（期间按钮禁用），约 0.6s 后进下一题。 */
  it("recordedFeedbackBeforeNextQuestion_ac6", async () => {
    renderPage();

    const input = await screen.findByLabelText("英文译文");
    await userEvent.setup().type(input, PLACEMENT_SENTENCES[0].ref);
    await userEvent.setup().click(screen.getByRole("button", { name: "提交" }));

    // 微反馈即时出现；反馈期间提交/跳过禁用，防连点双进题。
    expect(await screen.findByText("已记录 ✓")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "提交" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "跳过这题" })).toBeDisabled();

    // 约 0.6s 后进下一题，微反馈随之退场。
    await screen.findByText(PLACEMENT_SENTENCES[1].zh, {}, { timeout: 2000 });
    expect(screen.queryByText("已记录 ✓")).toBeNull();
  });

  /** 最后一题提交：微反馈后进结果屏（起点建议），不再出现题目。 */
  it("recordedFeedbackBeforeResultScreen_ac6", async () => {
    renderPage();

    await userEvent.setup().type(await screen.findByLabelText("英文译文"), "Anything at all.");
    await userEvent.setup().click(screen.getByRole("button", { name: "提交" }));
    await screen.findByText(PLACEMENT_SENTENCES[1].zh, {}, { timeout: 2000 });

    await userEvent.setup().type(await screen.findByLabelText("英文译文"), "Anything at all.");
    await userEvent.setup().click(screen.getByRole("button", { name: "提交" }));
    expect(await screen.findByText("已记录 ✓")).toBeInTheDocument();
    await screen.findByText(/先从「/, {}, { timeout: 2000 });
    expect(screen.queryByText("已记录 ✓")).toBeNull();
  });
});
