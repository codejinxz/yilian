import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AskPanel } from "@/features/practice/AskPanel";
import { ApiError } from "@/lib/api-error";
import { createQueryClient } from "@/lib/query";

/** askTutor 由本测试替换；默认成功回复。 */
const askTutorMock = vi.hoisted(() =>
  vi.fn<
    (sentenceId: string, question: string) => Promise<{ answer: string; disclaimer: string }>
  >(),
);

vi.mock("@/lib/api/practice", () => ({
  askTutor: askTutorMock,
}));

/** 挂载 AskPanel；AskPanel 用 useMutation，需要 QueryClient 包裹。 */
function renderPanel(overrides?: { onUnavailable?: () => void; onClose?: () => void }) {
  const onUnavailable = overrides?.onUnavailable ?? vi.fn();
  const onClose = overrides?.onClose ?? vi.fn();
  render(
    <QueryClientProvider client={createQueryClient()}>
      <AskPanel sentenceId="01HSENTENCE" onClose={onClose} onUnavailable={onUnavailable} />
    </QueryClientProvider>,
  );
  return { onUnavailable, onClose };
}

/** 输入问题并提交。 */
async function ask(question: string) {
  const user = userEvent.setup();
  await user.type(screen.getByRole("textbox"), question);
  await user.click(screen.getByRole("button", { name: "提问" }));
}

describe("AskPanel", () => {
  beforeEach(() => {
    askTutorMock.mockReset();
    askTutorMock.mockResolvedValue({ answer: "先看介词搭配。", disclaimer: "AI 生成，可能有误" });
  });

  /** [AC-5] 免责行常驻：未提问时也在，不随回答出现/消失。 */
  it("rendersPermanentDisclaimer_ac5", () => {
    renderPanel();
    expect(screen.getAllByText("AI 生成，可能有误").length).toBeGreaterThan(0);
    expect(screen.queryByRole("list")).toBeNull();
  });

  /** [AC-5] 提交流：问句乐观上屏，成功后回答与逐条免责标注出现，输入框清空。 */
  it("submitsQuestionAndShowsAnswer_ac5", async () => {
    renderPanel();
    await ask("这里介词为什么用 to？");

    await waitFor(() => {
      expect(screen.getByText("先看介词搭配。")).toBeTruthy();
    });
    expect(screen.getByText("这里介词为什么用 to？")).toBeTruthy();
    expect(askTutorMock).toHaveBeenCalledWith("01HSENTENCE", "这里介词为什么用 to？");
    // 逐条回答的免责标注（列表内）+ 常驻免责行（表单内）。
    expect(screen.getAllByText("AI 生成，可能有误").length).toBeGreaterThanOrEqual(2);
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe("");
  });

  /** [AC-5] 空问题不可提交；500 字上限由 maxLength 兜住，服务端契约在前端已闭合。 */
  it("disablesSubmitForEmptyQuestion_ac5", async () => {
    renderPanel();
    expect((screen.getByRole("button", { name: "提问" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByRole("textbox")).toHaveProperty("maxLength", 500);
    expect(askTutorMock).not.toHaveBeenCalled();
  });

  /** [AC-5] 40304/40400：调用 onUnavailable 通知父级隐藏入口，不展示普通错误文案。 */
  it("hidesEntryViaOnUnavailableWhenForbidden_ac5", async () => {
    askTutorMock.mockRejectedValue(new ApiError(403, 40304, "答疑未开放"));
    const { onUnavailable } = renderPanel();

    await ask("问");

    await waitFor(() => {
      expect(onUnavailable).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** [AC-5] 其他失败（如降级 50000）：保留问句、展示可重试的错误文案，不隐藏入口。 */
  it("showsRetryableErrorOnDegrade_ac5", async () => {
    askTutorMock.mockRejectedValue(new ApiError(500, 50000, "答疑暂时不可用，请稍后再试"));
    const { onUnavailable } = renderPanel();

    await ask("问");

    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeTruthy();
    });
    expect(screen.getByRole("alert").textContent).toContain("答疑暂时不可用");
    // 问句保留（「问」徽标 + 问句气泡），便于修改重问；入口未隐藏。
    expect(screen.getAllByText("问").length).toBeGreaterThanOrEqual(1);
    expect(onUnavailable).not.toHaveBeenCalled();
  });
});
