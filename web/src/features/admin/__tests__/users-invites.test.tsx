import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { UsersPage } from "@/features/admin/UsersPage";
import { resetApiState } from "@/lib/api";
import { createQueryClient } from "@/lib/query";

/**
 * 用户页邀请码区（D9）：列表显示完整码（会话内生成过才可得）+ 点击复制 + 已用/未用状态；
 * 前缀码如实回「仅生成时展示」，不伪造可复制的假明文（服务端只存哈希）。
 */

function userRow(): Record<string, unknown> {
  return {
    id: "3",
    username: "learner",
    email: "learner@example.com",
    role: "USER",
    enabled: true,
    totpEnabled: false,
    deleted: false,
    createdAt: "2026-01-15T08:00:00Z",
    lastLoginAt: "2026-11-10T12:30:00Z",
    disabledReason: null,
    attemptsCount: 5,
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

/** 用户页 fetch 桩：用户列表、邀请码列表与生成端点；会话端点按未登录打回。 */
function stubUsersApi(invites: Array<Record<string, unknown>>) {
  // 生成端点把新前缀入列：与真实后端一致——生成后再 GET 能看到新邀请码的展示前缀。
  const invitesState = [...invites];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    const pathname = url.pathname;
    const method = init?.method ?? "GET";
    if (pathname === "/api/auth/refresh" || pathname === "/api/auth/me") {
      return jsonResponse({ code: 40100, message: "请先登录", data: null }, 401);
    }
    if (pathname === "/api/admin/users" && method === "GET") {
      return jsonResponse(page([userRow()]));
    }
    if (pathname === "/api/admin/invites" && method === "GET") {
      return jsonResponse(ok({ items: invitesState }));
    }
    if (pathname === "/api/admin/invites" && method === "POST") {
      invitesState.unshift({
        codePrefix: "YL-QBZN",
        used: false,
        usedBy: null,
        createdAt: "2026-11-21T04:00:00Z",
      });
      return jsonResponse(
        ok({ code: "YL-QBZN-QAZ9", codePrefix: "YL-QBZN", createdAt: "2026-11-21T04:00:00Z" }),
      );
    }
    if (pathname === "/api/admin/audit-logs") {
      return jsonResponse(page([]));
    }
    return jsonResponse({ code: 40400, message: "请求的资源不存在", data: null }, 404);
  });
  vi.stubGlobal("fetch", fetchMock);
}

function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <UsersPage />
    </QueryClientProvider>,
  );
}

/** 读回 user-event 剪贴板桩的内容（其 readText 不在 TS DOM 类型里，收窄读取）。 */
function readClipboardText(): Promise<string> {
  const clipboard = window.navigator.clipboard as unknown as { readText(): Promise<string> };
  return clipboard.readText();
}

describe("UsersPage 邀请码列表", () => {
  beforeEach(() => {
    resetApiState();
    vi.unstubAllGlobals();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  /** [AC-2] 前缀码不伪造复制：点击如实提示「仅生成时展示」，不写剪贴板。 */
  it("prefixOnlyChipExplainsInsteadOfFakeCopy_ac2", async () => {
    stubUsersApi([
      { codePrefix: "YL-OLD", used: true, usedBy: "learner", createdAt: "2026-01-01T08:00:00Z" },
    ]);
    renderPage();
    const user = userEvent.setup();

    // 已用/未用状态显式可读；截断前缀仍在（服务端契约只回前缀）。
    await screen.findByText("YL-OLD");
    expect(screen.getByText("已用")).toBeTruthy();

    await user.click(screen.getByRole("button", { name: /YL-OLD/ }));
    expect(await screen.findByText(/完整码仅在生成时展示一次/)).toBeTruthy();
    // user-event 给 window.navigator 挂的剪贴板桩仍为空：没有伪造复制。
    await expect(readClipboardText()).resolves.toBe("");
  });

  /** [AC-2] 生成后的码以完整形态入列，点击复制明文并给出反馈。 */
  it("rendersFullCodeAndCopiesOnFreshInvite_ac2", async () => {
    stubUsersApi([
      { codePrefix: "YL-OLD", used: true, usedBy: "learner", createdAt: "2026-01-01T08:00:00Z" },
    ]);
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "生成" }));

    // 列表出现完整码（不再 8 位截断），状态为「未用」。
    await screen.findByText("YL-QBZN-QAZ9");
    expect(screen.getByText("未用")).toBeTruthy();

    await user.click(screen.getByRole("button", { name: /YL-QBZN-QAZ9/ }));
    expect(await screen.findByText("已复制邀请码 YL-QBZN-QAZ9")).toBeTruthy();
    // user-event 桩上的 readText 读回写入内容：复制的是完整明文而非 8 位前缀。
    await expect(readClipboardText()).resolves.toBe("YL-QBZN-QAZ9");
  });
});
