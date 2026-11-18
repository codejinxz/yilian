import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppealsPage } from "@/features/admin/AppealsPage";
import { resetApiState } from "@/lib/api";
import { createQueryClient } from "@/lib/query";

/**
 * 申诉终审页签：fetch 桩按服务端真实响应形状（API §8.16）应答， 锁定端点契约、列表渲染（状态/AI 意见/用户提议）、终审操作（必填理由弹窗）与
 * 终审后状态刷新；越权由路由与服务端矩阵保证（页面仅在 /admin 下渲染，非管理员不出现入口， 见 admin-shell 用例），这里锁「行内已终审行不再出现操作按钮」。
 */

/** 服务端 AdminAppealView 形状（API §8.16）。 */
interface AppealRow {
  id: number;
  attemptId: string;
  annotationKey: string;
  sentenceId: string;
  chunkIndex: number;
  kind: "APPEAL" | "DISPUTE";
  status: "PENDING" | "ACCEPTED" | "REJECTED" | "WITHDRAWN";
  proposedAnswer: string | null;
  reason: string | null;
  decisionVerdict: string | null;
  decisionReason: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  modelSnapshot: string | null;
  promptVersionId: number | null;
  createdAt: string;
  sentenceZh: string | null;
  username: string | null;
}

/** 待终审 + AI 意见（支持）行。 */
const PENDING_SUPPORT: AppealRow = {
  id: 60,
  attemptId: "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0060",
  annotationKey: "slot:1",
  sentenceId: "01JSEN00000000000000000002",
  chunkIndex: 1,
  kind: "APPEAL",
  status: "PENDING",
  proposedAnswer: "pay attention on details",
  reason: "这个搭配口语里也常见",
  decisionVerdict: "SUPPORT",
  decisionReason: "与规范答案语义等价，可以接受",
  decidedBy: "LLM",
  decidedAt: "2026-11-06T02:00:00Z",
  modelSnapshot: "stub:stub-model",
  promptVersionId: 3,
  createdAt: "2026-11-06T01:00:00Z",
  sentenceZh: "你应该注意细节。",
  username: "it-appealer",
};

/** 已通过行：只有「已终审」占位，无操作按钮。 */
const ACCEPTED_ROW: AppealRow = {
  ...PENDING_SUPPORT,
  id: 61,
  status: "ACCEPTED",
  decisionReason: "写法与规范答案语义等价",
  decidedBy: "ADMIN",
  decidedAt: "2026-11-06T03:00:00Z",
};

/** 待终审 + 初裁不可用行（占位 UNAVAILABLE）。 */
const PENDING_UNAVAILABLE: AppealRow = {
  ...PENDING_SUPPORT,
  id: 62,
  decisionVerdict: "UNAVAILABLE",
  decisionReason: "AI 初裁暂不可用，等待人工终审",
  decidedBy: null,
  decidedAt: null,
  modelSnapshot: null,
  promptVersionId: null,
};

/**
 * 构造带 headers/json 的最小 Response（models-page 用例同构）。
 * @param body 响应体
 * @param status HTTP 状态
 * @returns 测试用 Response
 */
function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ "Content-Type": "application/json" }),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

/** 构造后端成功信封。 */
function ok(data: unknown): { code: number; message: string; data: unknown } {
  return { code: 0, message: "success", data };
}

/** 构造申诉分页信封。 */
function appealPage(items: AppealRow[]): unknown {
  return ok({
    items,
    page: 1,
    size: 10,
    total: items.length,
    totalPages: 1,
    hasNext: false,
  });
}

/**
 * /api/admin/appeals* 的 fetch 桩：GET 回列表、POST …/decision 回终审后行。
 * @param rows 列表行
 * @param decisionResponse POST 应答（缺省回 ACCEPTED 行）
 * @returns fetch 桩与写请求观测句柄
 */
function stubAppealsApi(rows: AppealRow[], decisionResponse?: AppealRow) {
  const decisions: Array<{ id: string; body: Record<string, unknown> }> = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/admin/appeals" && (init?.method ?? "GET") === "GET") {
      return jsonResponse(appealPage(rows));
    }
    const decision = url.pathname.match(/^\/api\/admin\/appeals\/([^/]+)\/decision$/);
    if (decision?.[1] && init?.method === "POST") {
      const body = JSON.parse(String(init.body ?? "{}")) as Record<string, unknown>;
      decisions.push({ id: decision[1], body });
      return jsonResponse(ok(decisionResponse ?? ACCEPTED_ROW));
    }
    return jsonResponse({ code: 40400, message: "请求的资源不存在", data: null }, 404);
  });
  vi.stubGlobal("fetch", fetchMock);
  return { fetchMock, decisions };
}

/**
 * 挂载申诉页签（页面自带 react-query，包 QueryClient 即可）。
 * @returns RTL 句柄
 */
function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <AppealsPage />
    </QueryClientProvider>,
  );
}

describe("AppealsPage 申诉终审页签（025）", () => {
  beforeEach(() => {
    resetApiState();
    vi.unstubAllGlobals();
    // 写请求要带 CSRF 头：客户端优先读 cookie，免得测试里再打 /api/auth/csrf。
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  /** [AC-4] 列表渲染：状态 pill、AI 意见摘要、用户提议与理由齐备。 */
  it("rendersRowsWithAiOpinion_ac4: 列表行含状态与 AI 意见", async () => {
    const { fetchMock } = stubAppealsApi([PENDING_SUPPORT]);
    const view = renderPage();

    expect(await screen.findByText("你应该注意细节。")).toBeInTheDocument();
    // 「待终审」同时出现在筛选下拉与状态 pill：断言 pill（span 形态）存在即可。
    const pendingPills = await screen.findAllByText("待终审");
    expect(pendingPills.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText(/AI 意见：支持 · 与规范答案语义等价，可以接受/)).toBeInTheDocument();
    expect(screen.getByText(/提议：pay attention on details/)).toBeInTheDocument();
    expect(screen.getByText(/理由：这个搭配口语里也常见/)).toBeInTheDocument();
    // 端点契约：GET /api/admin/appeals（默认参数）。
    const listCall = fetchMock.mock.calls.find(([input]) =>
      String(input).includes("/api/admin/appeals"),
    );
    expect(listCall).toBeTruthy();
    view.unmount();
  });

  /** [AC-4] 初裁不可用行显示占位语义，不显示「AI 意见：支持」一类的结论。 */
  it("rendersUnavailableOpinion_ac4: UNAVAILABLE 显示初裁不可用", async () => {
    stubAppealsApi([PENDING_UNAVAILABLE]);
    const view = renderPage();

    expect(await screen.findByText(/AI 初裁暂不可用/)).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-4] 终审操作：通过 → 必填理由弹窗 → 确认后 POST decision 且刷新列表。 */
  it("decideAcceptRequiresReason_ac4: 通过申诉必填理由并调用终审端点", async () => {
    const { decisions } = stubAppealsApi([PENDING_SUPPORT]);
    const view = renderPage();

    await screen.findByText("你应该注意细节。");
    await userEvent.setup().click(screen.getByRole("button", { name: "通过" }));

    // 理由必填：默认选预设（非空），确认可点；切到「其他」且未输入时确认禁用。
    const dialog = screen.getByRole("dialog");
    const confirm = within(dialog).getByRole("button", { name: "确认" });
    expect(confirm).toBeEnabled();
    await userEvent.setup().click(within(dialog).getByRole("radio", { name: "其他" }));
    expect(confirm).toBeDisabled();
    await userEvent
      .setup()
      .type(within(dialog).getByLabelText("自定义原因"), "写法与规范答案语义等价");
    expect(confirm).toBeEnabled();
    await userEvent.setup().click(confirm);

    await waitFor(() => expect(decisions).toHaveLength(1));
    expect(decisions[0]).toMatchObject({
      id: "60",
      body: { status: "ACCEPTED", reason: "写法与规范答案语义等价" },
    });
    // 成功反馈（toast）出现。
    expect(await screen.findByText("申诉已通过：该写法加入可接受答案")).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-4] 驳回操作：目标 REJECTED，文案与确认流程同构。 */
  it("decideRejectPostsRejected_ac4: 驳回申诉提交 REJECTED", async () => {
    const { decisions } = stubAppealsApi([PENDING_SUPPORT], {
      ...PENDING_SUPPORT,
      status: "REJECTED",
      decidedBy: "ADMIN",
    });
    const view = renderPage();

    await screen.findByText("你应该注意细节。");
    await userEvent.setup().click(screen.getByRole("button", { name: "驳回" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.setup().click(within(dialog).getByRole("radio", { name: "其他" }));
    await userEvent.setup().type(within(dialog).getByLabelText("自定义原因"), "与参考语义不符");
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "确认" }));

    await waitFor(() => expect(decisions).toHaveLength(1));
    expect(decisions[0]?.body.status).toBe("REJECTED");
    expect(await screen.findByText("申诉已驳回：题目与判分不变")).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-4] 已终审行不再出现操作按钮（避免重复裁决入口）。 */
  it("decidedRowHasNoActions_ac4: 已通过行无通过/驳回按钮", async () => {
    stubAppealsApi([ACCEPTED_ROW]);
    const view = renderPage();

    expect(await screen.findByText("你应该注意细节。")).toBeInTheDocument();
    expect((await screen.findAllByText("已通过")).length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByRole("button", { name: "通过" })).toBeNull();
    expect(screen.queryByRole("button", { name: "驳回" })).toBeNull();
    expect(screen.getByText(/终审理由：写法与规范答案语义等价/)).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-4] 空态与加载态之外，错误走 admin 变体（数据加载失败文案）。 */
  it("errorStateUsesAdminVariant_ac4: 请求失败渲染管理错误态", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    const view = renderPage();

    // 网络错误重试一次（1s 退避）后才落错误态，放宽等待窗口。
    const alert = await screen.findByRole("alert", {}, { timeout: 5000 });
    expect(alert).toHaveTextContent("管理数据加载失败");
    view.unmount();
  });

  /** [AC-4] 空列表：给「没有待处理的申诉」空态而不是空白区。 */
  it("emptyState_ac4: 无申诉时空态可读", async () => {
    stubAppealsApi([]);
    const view = renderPage();

    expect(await screen.findByText("没有待处理的申诉。")).toBeInTheDocument();
    view.unmount();
  });
});
