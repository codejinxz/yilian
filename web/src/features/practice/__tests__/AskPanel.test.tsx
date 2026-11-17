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

  /** 40304：先在面板内展示「答疑未开放」定向文案并保留问句，约 1.5s 后才通知父级收起——不再是零提示静默消失。 */
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

  /** 40400：文案指向 YILIAN_LLM_* 环境变量配置（服务端只回通用 404 文案，前端展开）。 */
  it("showsConfigMissingMessageOn40400_def01", async () => {
    askTutorMock.mockRejectedValue(new ApiError(404, 40400, "请求的资源不存在"));
    const { onUnavailable } = renderPanel();

    await ask("问");

    expect(await screen.findByRole("alert")).toHaveTextContent("答疑功能未配置");
    expect(screen.getByRole("alert").textContent).toContain("YILIAN_LLM_*");
    expect(onUnavailable).not.toHaveBeenCalled();
    await waitFor(() => expect(onUnavailable).toHaveBeenCalledTimes(1), { timeout: 3000 });
  });

  /** 40306：预算强制门展示定向文案，问句与输入保留，面板不收起（预算是月度状态，恢复后入口无需重新发现）。 */
  it("showsBudgetExhaustedMessageOn40306WithoutCollapsing_ac5", async () => {
    askTutorMock.mockRejectedValue(
      new ApiError(403, 40306, "本月模型预算已用尽，请下月再试或联系管理员调整预算"),
    );
    const { onUnavailable } = renderPanel();

    await ask("这里介词为什么用 to？");

    // 定向文案：点明预算原因与两条出路（下月再试 / 管理员调整预算）。
    expect(await screen.findByRole("alert")).toHaveTextContent("本月模型预算已用尽");
    expect(screen.getByRole("alert").textContent).toContain("请下月再试");
    expect(screen.getByRole("alert").textContent).toContain("联系管理员调整预算");
    // 与 40304/40400 不同：面板不收起（onUnavailable 不触发），问句与输入都保留。
    expect(onUnavailable).not.toHaveBeenCalled();
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe(
      "这里介词为什么用 to？",
    );
  });

  /** 等待回复时写明「最长约 30 秒」的预期，回复到位后等待提示消失。 */
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

  /** 回答走 Markdown 渲染：粗体/行内代码/列表按结构化元素呈现，问句仍是纯文本。 */
  it("rendersMarkdownAnswerWithCodeListBold_ac4", async () => {
    askTutorMock.mockResolvedValue({
      answer:
        "重点看**时态搭配**，固定搭配用 `attach importance to`：\n\n- 先找名词短语\n- 再选介词",
      disclaimer: "AI 生成，可能有误",
    });
    renderPanel();

    await ask("这里介词为什么用 to？");

    // 粗体落 <strong>；行内代码落 <code>；列表落 ul>li 结构（会话气泡本身也是 li，
    // 因此按 Markdown 列表容器数 li，不按全屏 role 查询）。
    expect(await screen.findByText("时态搭配").then((el) => el.tagName)).toBe("STRONG");
    expect(screen.getByText("attach importance to").closest("code")).not.toBeNull();
    const markdownList = screen.getByText("先找名词短语").closest("ul");
    expect(markdownList?.querySelectorAll(":scope > li")).toHaveLength(2);
    // 问句不走 Markdown：原样以纯文本出现在问句气泡（不被特殊符号吞掉）。
    expect(screen.getByText("这里介词为什么用 to？")).toBeTruthy();
  });

  /** GFM 表格渲染 + 原始 HTML 防线：表格结构化；script/img 一律按纯文本展示不产生元素。 */
  it("rendersGfmTableAndEscapesRawHtml_ac4", async () => {
    askTutorMock.mockResolvedValue({
      answer:
        "| 语块 | 搭配 |\n| --- | --- |\n| attach | to |\n\n内联试试 <script>alert(1)</script> 与 <img src=x onerror=alert(1)>",
      disclaimer: "AI 生成，可能有误",
    });
    renderPanel();

    await ask("问");

    // GFM 表格：表头两列、行单元格齐全。
    expect(await screen.findByRole("table")).toBeTruthy();
    expect(screen.getAllByRole("columnheader")).toHaveLength(2);
    expect(screen.getAllByRole("cell")).toHaveLength(2);
    // 默认不渲染原始 HTML：回答气泡内不出现 script/img 元素，标记按纯文本可见。
    const answerBubble = screen.getByText(/<script>alert\(1\)<\/script>/).closest("li");
    expect(answerBubble).not.toBeNull();
    expect(answerBubble?.querySelector("script")).toBeNull();
    expect(answerBubble?.querySelector("img")).toBeNull();
    expect(screen.getByText(/<img src=x onerror/)).toBeTruthy();
  });
});
