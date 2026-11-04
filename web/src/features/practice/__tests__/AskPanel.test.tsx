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

  /** [017R2 DEF-01] 40304：先在面板内展示「答疑未开放」定向文案并保留问句，约 1.5s 后才通知父级收起——不再是零提示静默消失。 */
  it("showsUnavailableMessageThenCollapsesOn40304_def01", async () => {
    askTutorMock.mockRejectedValue(new ApiError(403, 40304, "答疑未开放"));
    const { onUnavailable } = renderPanel();

    await ask("这里介词为什么用 to？");

    // 先讲清原因：定向文案可见（对齐 API §6.12 三态语义），且不是普通错误文案。
    expect(await screen.findByRole("alert")).toHaveTextContent("答疑未开放");
    expect(screen.getByRole("alert").textContent).toContain("请先在管理端启用答疑");
    // 输入不丢：textarea 与已上屏的问句气泡都还在（受控 textarea 的值也是文本节点，
    // 两个匹配 = 气泡 + 输入框）。
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe(
      "这里介词为什么用 to？",
    );
    expect(screen.getAllByText("这里介词为什么用 to？").length).toBeGreaterThanOrEqual(2);
    // 收起被推迟：错误文案展示期间不立即触发 onUnavailable。
    expect(onUnavailable).not.toHaveBeenCalled();
    // 停留约 1.5s 后再收起（父级隐藏入口），用户看得到提示而不是面板凭空消失。
    await waitFor(() => expect(onUnavailable).toHaveBeenCalledTimes(1), { timeout: 3000 });
  });

  /** [017R2 DEF-01] 40400：文案指向 YILIAN_LLM_* 环境变量配置（服务端只回通用 404 文案，前端展开）。 */
  it("showsConfigMissingMessageOn40400_def01", async () => {
    askTutorMock.mockRejectedValue(new ApiError(404, 40400, "请求的资源不存在"));
    const { onUnavailable } = renderPanel();

    await ask("问");

    expect(await screen.findByRole("alert")).toHaveTextContent("答疑功能未配置");
    expect(screen.getByRole("alert").textContent).toContain("YILIAN_LLM_*");
    expect(onUnavailable).not.toHaveBeenCalled();
    await waitFor(() => expect(onUnavailable).toHaveBeenCalledTimes(1), { timeout: 3000 });
  });

  /** [017R2 OBS-2] 等待回复时写明「最长约 30 秒」的预期，回复到位后等待提示消失。 */
  it("showsPendingExpectationWhileWaiting_obs2", async () => {
    let resolveAsk: (reply: { answer: string; disclaimer: string }) => void = () => {};
    askTutorMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveAsk = resolve;
        }),
    );
    renderPanel();

    await ask("问");

    const pending = await screen.findByRole("status");
    expect(pending.textContent).toContain("最长约 30 秒");

    resolveAsk({ answer: "先看搭配。", disclaimer: "AI 生成，可能有误" });
    await waitFor(() => {
      expect(screen.getByText("先看搭配。")).toBeTruthy();
    });
    expect(screen.queryByRole("status")).toBeNull();
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
