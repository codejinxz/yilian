import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SentencePicker } from "@/features/practice/sentence-picker";
import { ApiError } from "@/lib/api-error";
import { createQueryClient } from "@/lib/query";
import type { PageResponse } from "@/lib/schemas/common";
import type { PickerSentence } from "@/lib/schemas/content";

/** getPickerSentences 桩（P-19 桩约定：选题列表 API 在模块边界整体替换）。 */
const getPickerSentencesMock = vi.hoisted(() =>
  vi.fn<
    (bankId: string, params: unknown, signal?: AbortSignal) => Promise<PageResponse<PickerSentence>>
  >(),
);

vi.mock("@/lib/api/content", () => ({
  getPickerSentences: getPickerSentencesMock,
}));

/** 选题面板的题库上下文形状（与组件 prop 同构）；句数由桩数据体现。 */
type PickerBank = { id: string; name: string; description: string | null; sentenceCount: number };

const BANK: PickerBank = {
  id: "7",
  name: "官方题库 · TEM8 翻译",
  description: "专八翻译语块",
  sentenceCount: 3,
};

/** 桩行：覆盖 PUBLISHED/DISABLED 两状态与三种练过态。 */
function pickerRows(): PickerSentence[] {
  const day = 86_400_000;
  return [
    {
      id: "01JROW00000000000000000001",
      zh: "他越来越重视这件事。",
      level: "TEM8",
      status: "PUBLISHED",
      reviewState: "LEARNING",
      reviewDueAt: new Date(Date.now() + 30 * day).toISOString(),
    },
    {
      id: "01JROW00000000000000000002",
      zh: "会议按计划举行。",
      level: null,
      status: "PUBLISHED",
      reviewState: null,
      reviewDueAt: null,
    },
    {
      id: "01JROW00000000000000000003",
      zh: "这是尚未发布的句子。",
      level: "CET4",
      status: "DISABLED",
      reviewState: "MASTERED",
      reviewDueAt: null,
    },
  ];
}

function pickerPage(items: PickerSentence[], hasNext = false): PageResponse<PickerSentence> {
  return { items, page: 1, size: 100, total: items.length, totalPages: 1, hasNext };
}

/** 挂载面板；组件用 useInfiniteQuery，需要 QueryClient 包裹。 */
function renderPicker(overrides?: {
  open?: boolean;
  bank?: PickerBank | null;
  currentSentenceId?: string | null;
  onJump?: (sentenceId: string) => void;
  onClose?: () => void;
}) {
  const onJump = overrides?.onJump ?? vi.fn();
  const onClose = overrides?.onClose ?? vi.fn();
  render(
    <QueryClientProvider client={createQueryClient()}>
      <SentencePicker
        open={overrides?.open ?? true}
        bank={overrides && "bank" in overrides ? (overrides.bank ?? null) : BANK}
        currentSentenceId={overrides?.currentSentenceId ?? null}
        onJump={onJump}
        onClose={onClose}
      />
    </QueryClientProvider>,
  );
  return { onJump, onClose };
}

describe("SentencePicker（AC-2/AC-3）", () => {
  beforeEach(() => {
    getPickerSentencesMock.mockReset();
    getPickerSentencesMock.mockResolvedValue(pickerPage(pickerRows()));
  });

  afterEach(() => {
    document.body.style.overflow = "";
  });

  /** [AC-2] 列表渲染：中文摘要/难度/状态徽标/练过态逐列落位；点选后的跳转语义另测。 */
  it("rendersRowsWithLevelStatusAndPracticeState_ac2", async () => {
    renderPicker();

    await waitFor(() => {
      expect(screen.getByText("他越来越重视这件事。")).toBeTruthy();
    });
    // 练过态：LEARNING 卡带相对到期描述；无卡行（匿名/未学）为「未学」。
    expect(screen.getByText(/学习中 · \d+ 天后到期/)).toBeTruthy();
    expect(screen.getByText(/未学/)).toBeTruthy();
    // 状态徽标：非 PUBLISHED 行打「已停用」，难度 CET4 → 四级。
    expect(screen.getByText(/已停用/)).toBeTruthy();
    expect(screen.getByText(/四级/)).toBeTruthy();
    expect(getPickerSentencesMock).toHaveBeenCalledWith("7", expect.anything(), expect.anything());
  });

  /** [AC-2] 点选已发布句触发 onJump（页内再走 next?sentenceId= 的 MANUAL 语义）。 */
  it("jumpsOnPublishedRowClick_ac2", async () => {
    const user = userEvent.setup();
    const { onJump } = renderPicker();

    await waitFor(() => {
      expect(screen.getByText("他越来越重视这件事。")).toBeTruthy();
    });
    await user.click(screen.getByText("他越来越重视这件事。"));

    expect(onJump).toHaveBeenCalledWith("01JROW00000000000000000001");
  });

  /** [AC-2] 非 PUBLISHED 行不可点选：停用句点了也不跳（练习需要已发布句）。 */
  it("doesNotJumpOnDisabledRow_ac2", async () => {
    const user = userEvent.setup();
    const { onJump } = renderPicker();

    await waitFor(() => {
      expect(screen.getByText("这是尚未发布的句子。")).toBeTruthy();
    });
    await user.click(screen.getByText("这是尚未发布的句子。"));

    expect(onJump).not.toHaveBeenCalled();
  });

  /** [AC-3] 当前句高亮（aria-current + 「当前」标记），关闭（Esc/遮罩/×）回到当前句。 */
  it("highlightsCurrentSentenceAndCloses_ac3", async () => {
    const user = userEvent.setup();
    const { onClose } = renderPicker({ currentSentenceId: "01JROW00000000000000000002" });

    await waitFor(() => {
      expect(screen.getByText("会议按计划举行。")).toBeTruthy();
    });
    expect(
      screen.getByText("会议按计划举行。").closest("button")?.getAttribute("aria-current"),
    ).toBe("true");
    expect(screen.getByText(/当前/)).toBeTruthy();

    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "关闭选题面板" }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  /** [AC-2] 游客与登录态一致可用：无卡的公开库行全部展示「未学」，照常可点选。 */
  it("rendersGuestRowsAsUnlearned_ac2", async () => {
    const user = userEvent.setup();
    getPickerSentencesMock.mockResolvedValue(
      pickerPage([
        {
          id: "01JROW00000000000000000009",
          zh: "公开库的句子。",
          level: null,
          status: "PUBLISHED",
          reviewState: null,
          reviewDueAt: null,
        },
      ]),
    );
    const { onJump } = renderPicker({
      bank: { id: "1", name: "公开题库", description: null, sentenceCount: 2 },
    });

    await waitFor(() => {
      expect(screen.getByText("公开库的句子。")).toBeTruthy();
    });
    expect(screen.getByText(/未学/)).toBeTruthy();

    await user.click(screen.getByText("公开库的句子。"));
    expect(onJump).toHaveBeenCalledWith("01JROW00000000000000000009");
  });

  /** [AC-2] 他人私有库（40300）等失败路径：错误文案降级、绝不渲染任何句子内容（原文默认不可见红线）。 */
  it("showsErrorWithoutSentenceContentOnForbidden_ac2", async () => {
    getPickerSentencesMock.mockRejectedValue(new ApiError(403, 40300, "没有访问权限", "response"));
    renderPicker();

    await waitFor(() => {
      expect(screen.getByText("句子列表加载失败，请稍后重试。")).toBeTruthy();
    });
    expect(screen.queryByText("他越来越重视这件事。")).toBeNull();
  });

  /** [AC-1] 无 bankId（他人私有库不在可见列表 → 页面入口不渲染同源）：bank=null 时不请求，降级为「题库不可用」而非永久加载态。 */
  it("showsBankUnavailableInsteadOfPendingWhenBankMissing_ac1", () => {
    renderPicker({ bank: null });

    expect(getPickerSentencesMock).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeTruthy();
    // 深链失效的降级态：明确「不可用」文案，绝不挂在「正在载入句子…」。
    expect(screen.getByText(/该题库不可用/)).toBeTruthy();
    expect(screen.queryByText("正在载入句子…")).toBeNull();
    expect(screen.queryByText("他越来越重视这件事。")).toBeNull();
  });

  /** [AC-1] 列表请求期间题库被下架/删除（40401）：同归「题库不可用」口径，不给重试误导。 */
  it("showsBankUnavailableOn40401_ac1", async () => {
    getPickerSentencesMock.mockRejectedValue(new ApiError(404, 40401, "题库不存在", "response"));
    renderPicker();

    await waitFor(() => {
      expect(screen.getByText(/该题库不可用/)).toBeTruthy();
    });
    expect(screen.queryByText("他越来越重视这件事。")).toBeNull();
  });

  /** [AC-1] 面板 portal 到 body：脱离壳层 .page 的层叠上下文，遮罩与 × 不再被 .topbar 盖住。 */
  it("rendersPanelIntoDocumentBody_ac1", () => {
    const { container } = render(
      <QueryClientProvider client={createQueryClient()}>
        <SentencePicker
          open
          bank={BANK}
          currentSentenceId={null}
          onJump={vi.fn()}
          onClose={vi.fn()}
        />
      </QueryClientProvider>,
    );

    // RTL 挂载容器里不应有对话框节点；面板经 portal 挂在 document.body 直下。
    expect(container.querySelector("[role='dialog']")).toBeNull();
    expect(document.body.querySelector("[role='dialog']")).toBeTruthy();
    // portal 后面板仍在文档流里可交互（× 按钮可达）。
    expect(document.body.querySelector("button[aria-label='关闭选题面板']")).toBeTruthy();
  });

  /** [AC-3] open=false 不渲染（一屏一句纪律：展开层关闭后练习页原样保留）。 */
  it("rendersNothingWhenClosed_ac3", () => {
    renderPicker({ open: false });
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
