import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BanksPage } from "@/features/admin/BanksPage";
import { resetApiState } from "@/lib/api";
import { createQueryClient } from "@/lib/query";

/** 壳层路由的 Link 桩：抽屉跳转只需渲染占位锚点，测试无需真实路由。 */
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children?: ReactNode }) => <a href="/practice">{children}</a>,
}));

/**
 * 题库抽屉句子行的「管理」直达（G9）：抽屉内复用题目页行内编辑弹窗，
 * 回收站句子不给编辑入口；保存走 PATCH /admin/sentences/{id} 且抽屉保留并刷新。
 */

function bankRow(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: "2",
    ownerId: null,
    name: "自建库",
    description: null,
    visibility: "PRIVATE",
    status: "PUBLISHED",
    version: 1,
    sentenceCount: 2,
    ...overrides,
  };
}

function sentenceRow(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: "s2",
    version: 1,
    sentenceVersionId: "sv2",
    questionVersion: 1,
    status: "PUBLISHED",
    deleted: false,
    zh: "他越来越重视环境保护。",
    ref: "He attaches importance to environmental protection.",
    note: null,
    chunks: [],
    blank: [],
    tags: ["环境"],
    level: "CUSTOM",
    ...overrides,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

function ok(data: unknown): { code: number; message: string; data: unknown } {
  return { code: 0, message: "success", data };
}

function page(items: Array<Record<string, unknown>>): unknown {
  return ok({
    items,
    page: 1,
    size: 10,
    total: items.length,
    totalPages: 1,
    hasNext: false,
  });
}

/** 题库页 fetch 桩：题库列表、抽屉句子、题目 PATCH 观测；会话端点按未登录打回。 */
function stubAdminApi(
  banks: Array<Record<string, unknown>>,
  sentences: Array<Record<string, unknown>>,
) {
  const patchCalls: Array<{ id: string; body: Record<string, unknown> }> = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    const pathname = url.pathname;
    const method = init?.method ?? "GET";
    if (pathname === "/api/auth/refresh" || pathname === "/api/auth/me") {
      return jsonResponse({ code: 40100, message: "请先登录", data: null }, 401);
    }
    if (pathname === "/api/admin/banks" && method === "GET") {
      return jsonResponse(page(banks));
    }
    if (/^\/api\/admin\/banks\/[^/]+\/sentences$/.test(pathname) && method === "GET") {
      return jsonResponse(page(sentences));
    }
    const patch = pathname.match(/^\/api\/admin\/sentences\/([^/]+)$/);
    if (patch && method === "PATCH") {
      const body = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
      patchCalls.push({ id: patch[1] ?? "", body });
      const source = sentences.find((item) => item.id === patch[1]) ?? sentenceRow();
      return jsonResponse(ok({ ...source, bankId: "2", ...body }));
    }
    return jsonResponse({ code: 40400, message: "请求的资源不存在", data: null }, 404);
  });
  vi.stubGlobal("fetch", fetchMock);
  return { patchCalls };
}

function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <BanksPage />
    </QueryClientProvider>,
  );
}

describe("BanksPage 抽屉句子行管理直达", () => {
  beforeEach(() => {
    resetApiState();
    vi.unstubAllGlobals();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  /** [AC-1] 抽屉句子行「管理」直达：复用题目页行内编辑弹窗；回收站行不给入口。 */
  it("opensSentenceEditModalFromDrawerRow_ac1", async () => {
    stubAdminApi(
      [bankRow()],
      [sentenceRow(), sentenceRow({ id: "s3", status: "RECYCLED", zh: "已回收的句子" })],
    );
    const view = renderPage();
    const user = userEvent.setup();

    await screen.findByText("自建库");
    await user.click(screen.getByRole("button", { name: "管理" }));

    const drawer = screen.getByRole("dialog");
    expect(within(drawer).getByText("他越来越重视环境保护。")).toBeTruthy();
    const manageButtons = within(drawer).getAllByRole("button", { name: "管理" });
    expect(manageButtons).toHaveLength(1);
    await user.click(manageButtons[0] as HTMLElement);

    const modal = screen
      .getAllByRole("dialog")
      .find((node) => within(node).queryByLabelText("中文原句"));
    if (!modal) {
      throw new Error("题目编辑弹窗未渲染");
    }
    expect(within(modal).getByLabelText("中文原句")).toHaveValue("他越来越重视环境保护。");
    view.unmount();
  });

  /** [AC-1] 弹窗保存走 PATCH 且带原因，成功后弹窗关闭、抽屉保留。 */
  it("savesDrawerSentenceEditThroughPatch_ac1", async () => {
    const { patchCalls } = stubAdminApi(
      [bankRow()],
      [sentenceRow(), sentenceRow({ id: "s3", status: "RECYCLED", zh: "已回收的句子" })],
    );
    renderPage();
    const user = userEvent.setup();

    await screen.findByText("自建库");
    await user.click(screen.getByRole("button", { name: "管理" }));
    const manageButtons = within(screen.getByRole("dialog")).getAllByRole("button", {
      name: "管理",
    });
    await user.click(manageButtons[0] as HTMLElement);

    const modal = screen
      .getAllByRole("dialog")
      .find((node) => within(node).queryByLabelText("中文原句"));
    if (!modal) {
      throw new Error("题目编辑弹窗未渲染");
    }
    await user.type(within(modal).getByLabelText("标签"), "，气候");
    await user.click(within(modal).getByRole("button", { name: "保存" }));

    await waitFor(() => expect(patchCalls).toHaveLength(1));
    expect(patchCalls[0]?.id).toBe("s2");
    expect(patchCalls[0]?.body.reason).toBe("编辑题目");
    expect(patchCalls[0]?.body.tags).toEqual(["环境", "气候"]);
    expect(await screen.findByText("已保存")).toBeTruthy();
  });
});
