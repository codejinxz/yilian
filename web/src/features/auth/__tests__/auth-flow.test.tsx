import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "@/app/providers";
import { createAppRouter } from "@/app/router";
import { getAccessToken, resetApiState, setAccessToken } from "@/lib/api";
import {
  createMemoryEngineStorage,
  savePlacementState,
  setEngineStorageForTests,
} from "@/lib/local-engine";
import { createQueryClient } from "@/lib/query";

const ADMIN = { id: 1, username: "admin", role: "ADMIN" } as const;
const LEARNER = { id: 2, username: "learner", role: "USER" } as const;

/** 壳层 AppShell 挂载后会拉取用户设置；字段须满足 settingsSchema，避免 parseData 抛「服务响应异常」。 */
const SETTINGS = {
  timezone: "Asia/Shanghai",
  dailyGoal: 3,
  ratingStandard: "TEM8",
  desiredRetention: 0.9,
  theme: "paper",
  notificationsEnabled: false,
} as const;

/** 今日启动台查询的合法夹具；日期只被页面格式化展示，用固定值即可，字段须满足 todaySchema。 */
const TODAY = {
  date: "2026-01-05",
  timezone: "Asia/Shanghai",
  dueCount: 2,
  doneCount: 1,
  goal: 3,
  correctRate: 0,
  streakDays: 1,
} as const;

/**
 * 壳层路由的定级门要求本地已有「已定级」状态；预置一份，让用例能直达今日等页面。
 * 通过 local-engine 的写入函数预置，避免测试耦合它的存储格式。
 */
function markPlaced(): void {
  savePlacementState({ placed: true, startLevel: "中级·日常", missedTags: [] });
}

/**
 * 构造一个带 headers/json/text 的最小 Response，避免新代码读取 Retry-After 时崩溃。
 * @param body 响应体
 * @param status HTTP 状态
 * @param headers 额外响应头
 * @returns 测试用 Response
 */
function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

/**
 * 构造后端成功信封。
 * @param data 业务数据
 * @returns code 为 0 的统一响应
 */
function successEnvelope(data: unknown): { code: number; message: string; data: unknown } {
  return { code: 0, message: "success", data };
}

/**
 * 构造登录或刷新成功响应，固定返回测试用的 admin 与 access token。
 * @returns 带认证数据的成功信封
 */
function authEnvelope(): {
  code: number;
  message: string;
  data: { accessToken: string; tokenType: string; expiresIn: number; user: typeof ADMIN };
} {
  return successEnvelope({
    accessToken: "access-token",
    tokenType: "Bearer",
    expiresIn: 900,
    user: ADMIN,
  }) as ReturnType<typeof authEnvelope>;
}

/**
 * 构造后端失败信封。
 * @param code 业务错误码
 * @param message 错误提示
 * @returns data 为 null 的失败响应
 */
function errorEnvelope(
  code: number,
  message: string,
): { code: number; message: string; data: null } {
  return { code, message, data: null };
}

/**
 * 登录成功或会话恢复后，壳层 AppShell 会发起设置与今日队列查询；
 * 这里统一返回合法夹具让业务查询正常完成，用例断言继续聚焦认证端点本身。
 * @param url 请求 URL
 * @returns 命中业务查询时的响应；未命中返回 null，由调用方按意外请求处理
 */
function businessQueryResponse(url: string): Response | null {
  if (url.endsWith("/api/practice/today")) {
    return jsonResponse(successEnvelope(TODAY));
  }
  if (url.endsWith("/api/me/settings")) {
    return jsonResponse(successEnvelope(SETTINGS));
  }
  return null;
}

/**
 * 以内存 history 渲染整个应用，避免测试依赖真实浏览器地址。
 * @param initialPath 初始路由路径
 * @returns 本次渲染使用的 router 与 queryClient
 */
function renderApp(initialPath: string) {
  const queryClient = createQueryClient();
  const router = createAppRouter({
    queryClient,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
  });
  render(<AppProviders router={router} queryClient={queryClient} />);
  return { router, queryClient };
}

describe("auth flow", () => {
  beforeEach(() => {
    resetApiState();
    // jsdom 没有 IndexedDB；注入内存存储让 local-engine 的定级门与读写正常工作。
    setEngineStorageForTests(createMemoryEngineStorage());
    window.localStorage.clear();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("logs in, sends the CSRF header, and lands on today", async () => {
    markPlaced();
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/login")) {
        expect(init?.method).toBe("POST");
        expect(init?.credentials).toBe("include");
        const headers = init?.headers as Headers;
        expect(headers.get("X-XSRF-TOKEN")).toBe("test-token");
        return jsonResponse(authEnvelope());
      }
      if (url.endsWith("/api/auth/me")) {
        const headers = init?.headers as Headers;
        expect(headers.get("Authorization")).toBe("Bearer access-token");
        return jsonResponse(successEnvelope(ADMIN));
      }
      const business = businessQueryResponse(url);
      if (business) {
        return business;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/login");
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("用户名或邮箱"), "admin");
    await user.type(screen.getByLabelText("密码"), "test-admin-password");
    await user.click(screen.getByRole("button", { name: "登录" }));

    // 等今日页进入成功态：「开始练习」链接只在拿到今日数据后渲染，比加载骨架更能证明落地完成。
    // CTA 无静态 aria-label，可访问名就是三态可见文案，用正则匹配。
    expect(await screen.findByRole("link", { name: /开始|继续|热身/ })).toBeInTheDocument();
    // 登录成功后跳转 /today（今日启动台），与首页 / 同内容。
    await waitFor(() => expect(router.state.location.pathname).toBe("/today"));
    expect(getAccessToken()).toBe("access-token");
    expect(fetchMock).toHaveBeenCalled();
  });

  it("shows the server message when login fails", async () => {
    // 登录页已并入壳层：壳层定级门要求本地已有「已定级」状态，预置一份让 /login 能渲染登录表单。
    markPlaced();
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/api/auth/login")) {
          return jsonResponse(errorEnvelope(40101, "用户名或密码错误"), 401);
        }
        throw new Error(`unexpected request: ${url}`);
      }),
    );

    const { router } = renderApp("/login");
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("用户名或邮箱"), "admin");
    await user.type(screen.getByLabelText("密码"), "wrong-password");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("用户名或密码错误");
    expect(router.state.location.pathname).toBe("/login");
    expect(getAccessToken()).toBeNull();
  });

  it("keeps guests on the app and sends first-time visitors to placement", async () => {
    // 不预置定级状态：游客冷启动应被引导到定级页，而不是被逼去登录。
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(errorEnvelope(40100, "请先登录"), 401);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/");

    await waitFor(() => expect(router.state.location.pathname).toBe("/placement"));
    expect(screen.queryByRole("heading", { name: "登录" })).not.toBeInTheDocument();
    expect(getAccessToken()).toBeNull();
  });

  it("redirects unauthenticated visitors from admin routes to login after refresh fails", async () => {
    // 登录页已并入壳层：壳层定级门要求本地已有「已定级」状态，预置一份让重定向能落在登录页；
    // 未定级游客访问管理区会被先引去定级（由定级门用例覆盖），这里只验证管理区 → 登录页本身。
    markPlaced();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(errorEnvelope(40100, "请先登录"), 401);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/admin?tab=banks");

    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    await waitFor(() => expect(router.state.location.pathname).toBe("/login"));
    // 断言认证链路只有 refresh：管理门与壳层 me 查询各触发一次 refresh（登录页并入壳层后是两次独立查询），
    // refresh 失败后绝不重放 /api/auth/me 或 /api/auth/login；非认证的今日查询不计入。
    const authUrls = fetchMock.mock.calls
      .map((call) => String(call[0]))
      .filter((url) => url.includes("/api/auth/"));
    expect(authUrls).not.toHaveLength(0);
    expect(authUrls.every((url) => url.includes("/api/auth/refresh"))).toBe(true);
  });

  it("renders the 403 screen for a signed-in non-admin opening admin routes", async () => {
    setAccessToken("access-token");
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/api/auth/me")) {
          return jsonResponse(successEnvelope(LEARNER));
        }
        throw new Error(`unexpected request: ${url}`);
      }),
    );

    renderApp("/admin?tab=banks");

    expect(
      await screen.findByRole("heading", { name: "这个页面只对管理员开放" }),
    ).toBeInTheDocument();
    expect(screen.getByText("当前身份：普通用户。管理员入口在登录页。")).toBeInTheDocument();
  });

  it("restores the session from the refresh cookie on cold start", async () => {
    markPlaced();
    let refreshCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/refresh")) {
        refreshCalls += 1;
        return jsonResponse(authEnvelope());
      }
      const business = businessQueryResponse(url);
      if (business) {
        return business;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/");

    expect(await screen.findByRole("heading", { name: "今日" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
    expect(getAccessToken()).toBe("access-token");
    // 冷启动的本意是「只发一次 refresh 恢复会话」：refresh 恰好 1 次，且全部请求里
    // 不应出现其他认证端点（me/login/logout）；业务查询已单独补桩，不参与该断言。
    expect(refreshCalls).toBe(1);
    const authUrls = fetchMock.mock.calls
      .map((call) => String(call[0]))
      .filter((url) => url.includes("/api/auth/"));
    expect(authUrls).toEqual([expect.stringContaining("/api/auth/refresh")]);
  });

  it("refreshes once and replays a request after access token expiry", async () => {
    markPlaced();
    setAccessToken("expired-token");
    let meCalls = 0;
    let refreshCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/me")) {
        meCalls += 1;
        if (meCalls === 1) {
          return jsonResponse(errorEnvelope(40100, "请先登录"), 401);
        }
        return jsonResponse(successEnvelope(ADMIN));
      }
      if (url.endsWith("/api/auth/refresh")) {
        refreshCalls += 1;
        return jsonResponse(authEnvelope());
      }
      // 会话恢复后壳层会拉设置与今日队列；补桩让业务查询正常完成，避免无关报错干扰断言。
      const business = businessQueryResponse(url);
      if (business) {
        return business;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/");

    expect(await screen.findByRole("heading", { name: "今日" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
    expect(refreshCalls).toBe(1);
    expect(meCalls).toBe(2);
    expect(getAccessToken()).toBe("access-token");
  });

  it("rotates CSRF once after a 40301 and retries the login", async () => {
    markPlaced();
    let csrfCalls = 0;
    let loginCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/csrf")) {
        csrfCalls += 1;
        // 模拟服务端 Set-Cookie，让重试走真实 cookie 路径。
        // biome-ignore lint/suspicious/noDocumentCookie: 测试需要模拟服务端 Set-Cookie
        document.cookie = "XSRF-TOKEN=fresh-token; path=/";
        return jsonResponse(
          successEnvelope({
            token: "fresh-token",
            headerName: "X-XSRF-TOKEN",
            parameterName: "csrf",
          }),
        );
      }
      if (url.endsWith("/api/auth/login")) {
        loginCalls += 1;
        const headers = init?.headers as Headers;
        if (loginCalls === 1) {
          expect(headers.get("X-XSRF-TOKEN")).toBe("test-token");
          return jsonResponse(errorEnvelope(40301, "CSRF 令牌无效"), 403);
        }
        expect(headers.get("X-XSRF-TOKEN")).toBe("fresh-token");
        return jsonResponse(authEnvelope());
      }
      if (url.endsWith("/api/auth/me")) {
        return jsonResponse(successEnvelope(ADMIN));
      }
      const business = businessQueryResponse(url);
      if (business) {
        return business;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/login");
    await userEvent.type(await screen.findByLabelText("用户名或邮箱"), "admin");
    await userEvent.type(screen.getByLabelText("密码"), "secret");
    await userEvent.click(screen.getByRole("button", { name: "登录" }));

    // 等今日页进入成功态：「开始练习」链接只在拿到今日数据后渲染（可访问名为三态可见文案）。
    expect(await screen.findByRole("link", { name: /开始|继续|热身/ })).toBeInTheDocument();
    // 登录成功后落在 /today（今日启动台），与首页 / 同内容。
    expect(router.state.location.pathname).toBe("/today");
    expect(csrfCalls).toBe(1);
    expect(loginCalls).toBe(2);
  });

  it("keeps the logout control out of the topbar and links the avatar to settings", async () => {
    markPlaced();
    // 退出登录入口已移入设置页（由设置页切片补集成覆盖）；顶栏只保留头像 → 设置。
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/api/auth/login")) {
          return jsonResponse(authEnvelope());
        }
        if (url.endsWith("/api/auth/me")) {
          return jsonResponse(successEnvelope(ADMIN));
        }
        const business = businessQueryResponse(url);
        if (business) {
          return business;
        }
        throw new Error(`unexpected request: ${url}`);
      }),
    );

    renderApp("/login");
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("用户名或邮箱"), "admin");
    await user.type(screen.getByLabelText("密码"), "test-admin-password");
    await user.click(screen.getByRole("button", { name: "登录" }));

    // 等今日页进入成功态：确认已登录并落在壳层内（可访问名为三态可见文案）。
    expect(await screen.findByRole("link", { name: /开始|继续|热身/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "退出" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "打开设置" })).toBeInTheDocument();
    expect(getAccessToken()).toBe("access-token");
  });
});
