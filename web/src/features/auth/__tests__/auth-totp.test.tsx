import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "@/app/providers";
import { createAppRouter } from "@/app/router";
import { resetApiState } from "@/lib/api";
import {
  createMemoryEngineStorage,
  savePlacementState,
  setEngineStorageForTests,
} from "@/lib/local-engine";
import { createQueryClient } from "@/lib/query";

/**
 * 登录两步验证（0232 [AC-5]）交互测试：密码步 40305 → 第二步表单渲染（验证码/恢复码切换）
 * → 提交 login/totp 完成登录；挑战过期回第一步。fetch 桩按真实响应形状应答。
 */

const ADMIN = { id: 1, username: "admin", role: "ADMIN" } as const;

/** 壳层登录后必发的业务查询夹具（字段须满足对应 schema）。 */
const SETTINGS = {
  timezone: "Asia/Shanghai",
  dailyGoal: 3,
  ratingStandard: "TEM8",
  desiredRetention: 0.9,
  theme: "paper",
  notificationsEnabled: false,
} as const;

const TODAY = {
  date: "2026-01-05",
  timezone: "Asia/Shanghai",
  dueCount: 2,
  doneCount: 1,
  goal: 3,
  correctRate: 0,
  streakDays: 1,
} as const;

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

function renderApp() {
  const queryClient = createQueryClient();
  const router = createAppRouter({
    queryClient,
    history: createMemoryHistory({ initialEntries: ["/login"] }),
  });
  render(<AppProviders router={router} queryClient={queryClient} />);
  return { router };
}

describe("LoginPage 两步验证第二步", () => {
  beforeEach(() => {
    resetApiState();
    setEngineStorageForTests(createMemoryEngineStorage());
    window.localStorage.clear();
    // 壳层路由的定级门要求「已定级」状态：预置一份，让 /login 直达（auth-flow 同款）。
    savePlacementState({ placed: true, startLevel: "中级·日常", missedTags: [] });
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  /** [AC-5] 密码步 40305 → 第二步渲染 → 验证码登录成功进今日页。 */
  it("showsSecondStepOn40305AndCompletesLogin_ac5", async () => {
    const loginTotpCalls: Array<Record<string, unknown>> = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/csrf")) {
        return jsonResponse(
          ok({ token: "test-token", headerName: "X-XSRF-TOKEN", parameterName: "_csrf" }),
        );
      }
      if (url.endsWith("/api/auth/login")) {
        // 密码步：40305 + data 挑战载荷（真实信封形状）。
        return jsonResponse(
          {
            code: 40305,
            message: "需要两步验证",
            data: { challengeToken: "challenge-token-1", expiresIn: 300 },
          },
          403,
        );
      }
      if (url.endsWith("/api/auth/login/totp")) {
        const body = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
        loginTotpCalls.push(body);
        if (body.code !== "12345678") {
          return jsonResponse({ code: 40101, message: "动态验证码不正确", data: null }, 401);
        }
        return jsonResponse(
          ok({
            accessToken: "access-token",
            tokenType: "Bearer",
            expiresIn: 900,
            user: ADMIN,
            remainingRecoveryCodes: null,
          }),
        );
      }
      if (url.endsWith("/api/auth/me")) {
        return jsonResponse(ok(ADMIN));
      }
      if (url.endsWith("/api/me/settings")) {
        return jsonResponse(ok(SETTINGS));
      }
      if (url.endsWith("/api/practice/today")) {
        return jsonResponse(ok(TODAY));
      }
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse({ code: 40100, message: "请先登录", data: null }, 401);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("用户名或邮箱"), "admin");
    await user.type(screen.getByLabelText("密码"), "test-admin-password");
    await user.click(screen.getByRole("button", { name: "登录" }));

    // 第二步渲染：动态验证码输入 + 挑战说明，页签与演示提示消失。
    expect(await screen.findByText(/密码已验证/)).toBeInTheDocument();
    expect(screen.getByLabelText("动态验证码")).toBeInTheDocument();
    expect(screen.queryByLabelText("用户名或邮箱")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "验证并登录" })).toBeInTheDocument();

    // 错码：显示服务端文案，留在第二步。
    await user.type(screen.getByLabelText("动态验证码"), "00000000");
    await user.click(screen.getByRole("button", { name: "验证并登录" }));
    expect(await screen.findByText("动态验证码不正确")).toBeInTheDocument();

    // 正确动态码：登录成功进今日页；请求体回传 challengeToken 与 code。
    // 失败提示段（tabIndex=-1）在出错后持有焦点：先点回输入框再输入，模拟真实用户行为。
    const totpInput = screen.getByLabelText("动态验证码");
    await user.click(totpInput);
    await user.type(totpInput, "12345678");
    await user.click(screen.getByRole("button", { name: "验证并登录" }));
    expect(await screen.findByRole("link", { name: /开始|继续|热身/ })).toBeInTheDocument();
    await vi.waitFor(() => expect(router.state.location.pathname).toBe("/today"));
    expect(loginTotpCalls.at(-1)).toEqual({
      challengeToken: "challenge-token-1",
      code: "12345678",
    });
  });

  /** [AC-5] 恢复码模式切换与挑战过期回第一步。 */
  it("togglesRecoveryModeAndResetsOnExpiredChallenge_ac5", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/csrf")) {
        return jsonResponse(
          ok({ token: "test-token", headerName: "X-XSRF-TOKEN", parameterName: "_csrf" }),
        );
      }
      if (url.endsWith("/api/auth/login")) {
        return jsonResponse(
          {
            code: 40305,
            message: "需要两步验证",
            data: { challengeToken: "challenge-token-2", expiresIn: 300 },
          },
          403,
        );
      }
      if (url.endsWith("/api/auth/login/totp")) {
        // 挑战过期（5 分钟 TTL）：统一 40101「重新登录」口径 → 前端回第一步。
        return jsonResponse({ code: 40101, message: "登录已过期，请重新登录", data: null }, 401);
      }
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse({ code: 40100, message: "请先登录", data: null }, 401);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("用户名或邮箱"), "admin");
    await user.type(screen.getByLabelText("密码"), "test-admin-password");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByText(/密码已验证/)).toBeInTheDocument();

    // 切换恢复码模式：label 与切换文案互换。
    await user.click(screen.getByRole("button", { name: "验证器丢失？改用恢复码" }));
    expect(screen.getByLabelText("恢复码")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "改用验证器动态码" })).toBeInTheDocument();

    // 提交后挑战过期：回到第一步（第一步表单重新出现），第二步文案消失。
    await user.type(screen.getByLabelText("恢复码"), "10101010");
    await user.click(screen.getByRole("button", { name: "验证并登录" }));
    expect(await screen.findByLabelText("用户名或邮箱")).toBeInTheDocument();
    expect(screen.queryByText(/密码已验证/)).not.toBeInTheDocument();
  });
});
