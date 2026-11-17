import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { UsersPage } from "@/features/admin/UsersPage";
import { resetApiState } from "@/lib/api";
import { createQueryClient } from "@/lib/query";

/**
 * 管理端用户页 2FA 投影与重置（0232 [AC-6]）：列表 2FA 列（已开启/—）、抽屉状态行、
 * 重置两步验证走必填原因弹窗并调用 reset-2fa 端点；越权由路由与服务端矩阵保证。
 */

/** 服务端 AdminUserView 形状（API §8.1，含 0232 新增 totpEnabled）。 */
function userRow(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
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

/** 用户页全套 fetch 桩：列表、抽屉练习记录、审计、me/refresh、reset-2fa 观测。 */
function stubUsersApi(users: Array<Record<string, unknown>>) {
  const resetCalls: Array<{ id: string; body: Record<string, unknown> }> = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    const pathname = url.pathname;
    const method = init?.method ?? "GET";
    if (pathname === "/api/auth/refresh") {
      return jsonResponse({ code: 40100, message: "请先登录", data: null }, 401);
    }
    if (pathname === "/api/auth/me") {
      return jsonResponse({ code: 40100, message: "请先登录", data: null }, 401);
    }
    if (pathname === "/api/admin/users" && method === "GET") {
      const role = url.searchParams.get("role");
      if (role === "ADMIN") {
        // 抽屉的「活跃管理员计数」查询：size=1 只取 total。
        return jsonResponse(page([{ ...userRow({ id: "1", username: "admin", role: "ADMIN" }) }]));
      }
      return jsonResponse(page(users));
    }
    if (pathname === "/api/admin/invites") {
      return jsonResponse(ok({ items: [] }));
    }
    if (pathname === "/api/admin/audit-logs") {
      return jsonResponse(page([]));
    }
    const attempts = pathname.match(/^\/api\/admin\/users\/([^/]+)\/attempts$/);
    if (attempts && method === "GET") {
      return jsonResponse(
        ok({
          metrics: { totalAttempts: 5, avgScore: 80, activeDays30: 2, streakDays: 1 },
          heat: [],
          items: [],
        }),
      );
    }
    const reset2fa = pathname.match(/^\/api\/admin\/users\/([^/]+)\/reset-2fa$/);
    if (reset2fa && method === "POST") {
      const body = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
      resetCalls.push({ id: reset2fa[1] ?? "", body });
      return jsonResponse(ok(null));
    }
    return jsonResponse({ code: 40400, message: "请求的资源不存在", data: null }, 404);
  });
  vi.stubGlobal("fetch", fetchMock);
  return { resetCalls };
}

function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <UsersPage />
    </QueryClientProvider>,
  );
}

describe("UsersPage 两步验证列与重置", () => {
  beforeEach(() => {
    resetApiState();
    vi.unstubAllGlobals();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  /** [AC-6] 列表 2FA 列：启用者显示「已开启」，未启用显示占位破折号。 */
  it("rendersTotpColumn_ac6", async () => {
    stubUsersApi([userRow(), userRow({ id: "9", username: "guard", totpEnabled: true })]);
    const view = renderPage();

    expect(await screen.findByText("learner")).toBeInTheDocument();
    expect(screen.getByText("guard")).toBeInTheDocument();
    // 已开启与占位各一次（启用行有「已开启」，另一行是「—」）。
    expect(screen.getByText("已开启")).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-6] 抽屉重置两步验证：必填原因弹窗（预设理由）→ POST reset-2fa 且携带原因。 */
  it("reset2faThroughReasonDialog_ac6", async () => {
    const { resetCalls } = stubUsersApi([userRow({ totpEnabled: true })]);
    const view = renderPage();
    const user = userEvent.setup();

    await screen.findByText("learner");
    await user.click(screen.getByText("learner"));

    // 抽屉：状态行 + 重置两步验证入口。
    const drawer = screen.getByRole("dialog");
    expect(within(drawer).getByText(/已开启（登录需动态码）/)).toBeInTheDocument();
    await user.click(within(drawer).getByRole("button", { name: "重置两步验证" }));

    // 填原因弹窗：默认选中第一个预设即可确认（与重置密码同款交互）。
    // 抽屉与弹窗都是 dialog 角色：取「重置两步验证」标题命中的那一个。
    const dialogs = screen.getAllByRole("dialog");
    const reasonDialog = dialogs.find((node) => within(node).queryByText(/清空该用户的 TOTP 注册/));
    if (!reasonDialog) {
      throw new Error("重置两步验证弹窗未渲染");
    }
    await user.click(within(reasonDialog).getByRole("button", { name: "确认" }));
    expect(resetCalls).toHaveLength(1);
    expect(resetCalls[0]?.id).toBe("3");
    expect(resetCalls[0]?.body.reason).toBe("用户丢失验证器");
    // 成功后 toast 提示可见（可见、可撤销纪律：动作结果有明确反馈）。
    expect(await screen.findByText(/已重置两步验证/)).toBeInTheDocument();
    view.unmount();
  });
});
