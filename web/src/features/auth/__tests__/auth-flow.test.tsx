import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "@/app/providers";
import { createAppRouter } from "@/app/router";
import { useSessionStore } from "@/features/auth/session";
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
 * memory history 的 initialEntries 只收字符串（对象会被当 href 解析），带 state 的
 * 初始场景在构造 router 前用 history.replace 播种。
 * @param initialPath 初始路由路径
 * @param initialState 可选的 history state（跨路由提示等场景）
 * @returns 本次渲染使用的 router 与 queryClient
 */
function renderApp(initialPath: string, initialState?: unknown) {
  const queryClient = createQueryClient();
  const history = createMemoryHistory({ initialEntries: [initialPath] });
  if (initialState !== undefined) {
    history.replace(initialPath, initialState);
  }
  const router = createAppRouter({ queryClient, history });
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

  it("D-1: 删号成功提示经 history state 送达登录页一次性渲染并清除", async () => {
    // 跨路由提示链路：删号页 navigate 带 { flash } state → 登录页挂载取入
    // 组件态渲染 Notice（role=status），同时清掉 history state——刷新/后退不重放。
    // 不许用来源页 toast（随导航卸载即丢失，DOM 级监听证实从未进 DOM）。
    // markPlaced 防止壳层定级门把未定级游客先劫去 /placement（那里也有 role=status 骨架）。
    markPlaced();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/captcha-image")) {
        return jsonResponse(successEnvelope({ enabled: false, captchaId: "", imageBase64: "" }));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/login", {
      flash: "账号已删除：30 天冷静期后匿名化。感谢使用译练。",
    });

    const notice = await screen.findByRole("status");
    expect(notice.textContent).toContain("账号已删除：30 天冷静期后匿名化");
    // state 已清除（提示在组件态，history 里不再携带）。
    await waitFor(() => expect(router.state.location.state?.flash).toBeUndefined());
    // 登录表单仍正常渲染（提示不抢占页面功能）。
    expect(await screen.findByLabelText("用户名或邮箱")).toBeInTheDocument();
  });

  it("023 AC-1: logs in after a guest cold load and refetches anonymously cached banks so private banks appear", async () => {
    // 冷加载竞态：游客先到今日页，题库 chips 以匿名口径发出并被缓存（staleTime 内
    // 不自愈）；登录确认后必须失效 content 组重取，否则私有库在缓存过期前一直缺失。
    markPlaced();
    const publicBank = {
      id: "1",
      ownerId: null,
      name: "官方题库 · TEM8 翻译",
      description: null,
      visibility: "PUBLIC",
      status: "PUBLISHED",
      version: 1,
      sentenceCount: 8,
    };
    const privateBank = {
      ...publicBank,
      id: "9",
      name: "我的私有题库 · 私享",
      visibility: "PRIVATE",
      sentenceCount: 4,
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(errorEnvelope(40100, "请先登录"), 401);
      }
      if (url.endsWith("/api/auth/login")) {
        return jsonResponse(authEnvelope());
      }
      if (url.endsWith("/api/auth/me")) {
        return jsonResponse(successEnvelope(ADMIN));
      }
      if (url.includes("/api/banks?")) {
        // 匿名只回公开库；带 Bearer 的重取才回私有库——chip 出现即证明重取发生。
        const authed = Boolean(
          new Headers((init as RequestInit | undefined)?.headers as HeadersInit | undefined).get(
            "Authorization",
          ),
        );
        const items = authed ? [publicBank, privateBank] : [publicBank];
        return jsonResponse(
          successEnvelope({
            items,
            page: 1,
            size: 100,
            total: items.length,
            totalPages: 1,
            hasNext: false,
          }),
        );
      }
      if (url.includes("/api/banks/") && url.includes("/sentences?")) {
        return jsonResponse(successEnvelope({ version: 1, items: [], nextSinceVersion: 1 }));
      }
      if (url.includes("/api/analytics/accuracy")) {
        return jsonResponse(
          successEnvelope({
            from: "2026-01-01",
            to: "2026-01-05",
            totalAttempts: 0,
            correctRate: null,
            averageScore: null,
            byMode: [],
            daily14: [],
            recent: [],
          }),
        );
      }
      const business = businessQueryResponse(url);
      if (business) {
        return business;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/today");
    const user = userEvent.setup();
    // 题库 chips 默认折叠：展开后才能断言游客可见的题库集合。
    await user.click(await screen.findByRole("button", { name: /更多题库/ }));
    // 游客首屏：chips 只有公开库。
    expect(await screen.findByText(/官方题库/)).toBeInTheDocument();
    expect(screen.queryByText(/我的私有题库/)).toBeNull();

    // 登录成功回今日页：banks 查询失效重取后私有库 chip 可见（页面重挂载后 chips
    // 恢复折叠，需要再次展开）。
    await router.navigate({ to: "/login" });
    await user.type(await screen.findByLabelText("用户名或邮箱"), "admin");
    await user.type(screen.getByLabelText("密码"), "test-admin-password");
    await user.click(screen.getByRole("button", { name: "登录" }));

    await user.click(await screen.findByRole("button", { name: /更多题库/ }));
    expect(await screen.findByText(/我的私有题库/)).toBeInTheDocument();
    const authedBankCalls = fetchMock.mock.calls.filter(([input, init]) => {
      if (!String(input).includes("/api/banks?")) {
        return false;
      }
      // headers 可能是 Headers 实例或普通对象，统一包一层再读。
      const headers = new Headers(
        (init as RequestInit | undefined)?.headers as HeadersInit | undefined,
      );
      return Boolean(headers.get("Authorization"));
    });
    expect(authedBankCalls.length).toBeGreaterThanOrEqual(1);
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
      if (url.endsWith("/api/auth/captcha-image")) {
        // 登录页挂载即取图形码；给规范响应让页面走完整路径（取码失败本可优雅降级）。
        return jsonResponse(successEnvelope({ captchaId: "captcha-1", imageBase64: "cG5n" }));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { router } = renderApp("/admin?tab=banks");

    expect(await screen.findByRole("heading", { name: "登录" })).toBeInTheDocument();
    await waitFor(() => expect(router.state.location.pathname).toBe("/login"));
    // 断言认证链路只有 refresh：管理门与壳层 me 查询各触发一次 refresh（登录页并入壳层后是两次独立查询），
    // refresh 失败后绝不重放 /api/auth/me 或 /api/auth/login；图形码是登录页挑战、非认证链路，
    // 非认证的今日查询不计入。
    const authUrls = fetchMock.mock.calls
      .map((call) => String(call[0]))
      .filter((url) => url.includes("/api/auth/") && !url.includes("/api/auth/captcha-image"));
    expect(authUrls).not.toHaveLength(0);
    expect(authUrls.every((url) => url.includes("/api/auth/refresh"))).toBe(true);
  });

  it("renders the 403 screen for a signed-in non-admin opening admin routes", async () => {
    // 管理区已并入壳层：壳层定级门在管理门之前生效，未定级访问会先被引去 /placement；
    // 预置已定级状态让本用例继续聚焦管理门自身的 403 分流（游客重定向由上一用例覆盖）。
    markPlaced();
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

  it("027 AC-11: cold-start session restore marks the zustand session authenticated so gated UI stays consistent", async () => {
    // 刷新后经 refresh cookie 恢复会话：me 查询写入缓存即视为「已确认登录」，
    // Zustand 登录态同步置位——练习页「让模型复核」等按 sessionStatus 门控的 UI
    // 在恢复路径下此前停留在 unknown，已登录用户看不到复核入口。
    markPlaced();
    useSessionStore.getState().setAnonymous();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(authEnvelope());
      }
      const business = businessQueryResponse(url);
      if (business) {
        return business;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderApp("/");

    expect(await screen.findByRole("heading", { name: "今日" })).toBeInTheDocument();
    expect(getAccessToken()).toBe("access-token");
    expect(useSessionStore.getState().status).toBe("authenticated");
  });

  it("027 AC-11: anonymous cold start never marks the session authenticated", async () => {
    // 反向口径：refresh 401（无会话）时不得把登录态置成 authenticated，
    // 否则游客会看到只对登录者开放的入口。
    markPlaced();
    // store 是模块级单例，前序用例可能留下 authenticated；显式回游客态再验证不置位。
    useSessionStore.getState().setAnonymous();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/auth/refresh")) {
        return jsonResponse(errorEnvelope(40100, "请先登录"), 401);
      }
      const business = businessQueryResponse(url);
      if (business) {
        return business;
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderApp("/");

    expect(await screen.findByRole("heading", { name: "今日" })).toBeInTheDocument();
    expect(useSessionStore.getState().status).not.toBe("authenticated");
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
    // 退出登录入口已移入设置页（集成覆盖见设置页测试）；顶栏只保留头像 → 设置。
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
