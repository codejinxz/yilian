import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "@/app/providers";
import { createAppRouter } from "@/app/router";
import { resetApiState, setAccessToken } from "@/lib/api";
import {
  createMemoryEngineStorage,
  savePlacementState,
  setEngineStorageForTests,
} from "@/lib/local-engine";
import { createQueryClient } from "@/lib/query";

const ADMIN = { id: 1, username: "admin", role: "ADMIN" } as const;

/** 壳层 AppShell 挂载后会拉取用户设置；字段须满足 settingsSchema，避免 parseData 抛「服务响应异常」。 */
const SETTINGS = {
  timezone: "Asia/Shanghai",
  dailyGoal: 3,
  ratingStandard: "TEM8",
  desiredRetention: 0.9,
  theme: "paper",
  notificationsEnabled: false,
} as const;

/** 今日启动台查询的合法夹具；壳层始终启用该查询，字段须满足 todaySchema。 */
const TODAY = {
  date: "2026-01-05",
  timezone: "Asia/Shanghai",
  dueCount: 2,
  doneCount: 1,
  goal: 3,
  correctRate: 0,
  streakDays: 1,
} as const;

/** 用户页签桩数据：一行普通用户；形状须满足 adminUserPageSchema（含 PageResponse 信封字段）。 */
const USERS_PAGE = {
  items: [
    {
      id: "1001",
      username: "demo-user",
      email: "demo-user@example.com",
      role: "USER",
      enabled: true,
      deleted: false,
      createdAt: "2026-01-01T08:00:00Z",
      lastLoginAt: "2026-01-05T08:00:00Z",
      disabledReason: null,
    },
  ],
  page: 1,
  size: 10,
  total: 1,
  totalPages: 1,
  hasNext: false,
} as const;

/** 邀请码桩数据；getInvites 期望 { items: InviteSummary[] }，列表只给展示前缀。 */
const INVITES = {
  items: [
    { codePrefix: "YILIAN-DEMO", used: false, usedBy: null, createdAt: "2026-01-01T08:00:00Z" },
  ],
} as const;

/** 概览页签桩数据：空数组即可让概览渲染真实内容，作为页签切换的前像。 */
const OVERVIEW = {
  metrics: { totalUsers: 12, activeToday: 3, totalAttempts: 456, estimatedCost: 1.5 },
  active7d: [],
  todos: [],
  activity: [],
} as const;

/**
 * 题库页签桩数据：第一行可编辑、第二行已回收——回收库按 mock 契约在缺省列表出现，
 * 行内必须有「已回收」标注而不是被隐藏。
 */
const BANKS_PAGE = {
  items: [
    {
      id: "2",
      ownerId: null,
      name: "编辑目标库",
      description: null,
      visibility: "PRIVATE",
      status: "PUBLISHED",
      version: 3,
      sentenceCount: 5,
    },
    {
      id: "3",
      ownerId: null,
      name: "回收站里的库",
      description: null,
      visibility: "PRIVATE",
      status: "RECYCLED",
      version: 9,
      sentenceCount: 0,
    },
  ],
  page: 1,
  size: 10,
  total: 2,
  totalPages: 1,
  hasNext: false,
} as const;

/** 题库抽屉的句子桩数据：一行已回收句子（管理端视图带完整快照，不做 tombstone 裁剪）。 */
const BANK_SENTENCES_PAGE = {
  items: [
    {
      id: "01JZZZZZZZZZZZZZZZZZZZZZZZ",
      version: 3,
      sentenceVersionId: "11",
      questionVersion: 1,
      status: "RECYCLED",
      deleted: true,
      zh: "被回收的句子。",
      ref: "A recycled sentence.",
      note: null,
      chunks: [],
      blank: [],
      tags: [],
      level: null,
    },
  ],
  page: 1,
  size: 20,
  total: 1,
  totalPages: 1,
  hasNext: false,
} as const;

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
 * 管理端测试的统一 fetch 桩：me 固定返回管理员，壳层的设置/今日查询与管理端各页签端点
 * 都给规范应答，让用例断言聚焦壳层结构与管理布局本身。
 * @returns 已配置的 fetch mock
 */
function adminFetchMock() {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith("/api/auth/me")) {
      return jsonResponse(successEnvelope(ADMIN));
    }
    if (url.endsWith("/api/me/settings")) {
      return jsonResponse(successEnvelope(SETTINGS));
    }
    if (url.endsWith("/api/practice/today")) {
      return jsonResponse(successEnvelope(TODAY));
    }
    if (url.endsWith("/api/admin/overview")) {
      return jsonResponse(successEnvelope(OVERVIEW));
    }
    if (url.includes("/api/admin/users")) {
      return jsonResponse(successEnvelope(USERS_PAGE));
    }
    if (url.endsWith("/api/admin/invites")) {
      return jsonResponse(successEnvelope(INVITES));
    }
    throw new Error(`unexpected request: ${url}`);
  });
}

/**
 * 壳层路由的定级门要求本地已有「已定级」状态；管理区并入壳层后同样受门约束，
 * 预置一份让用例能直达 /admin（未定级先去定级的行为由定级门自身语义保证）。
 */
function markPlaced(): void {
  savePlacementState({ placed: true, startLevel: "中级·日常", missedTags: [] });
}

/**
 * 以内存 history 渲染整个应用，避免测试依赖真实浏览器地址。
 * 预置 access token 让 me() 直连 /api/auth/me 桩：否则 me() 先走 refresh 失败，
 * ApiError(0) 会触发一次 1 秒退避的查询重试，把管理门的放行拖到断言超时之后。
 * @param initialPath 初始路由路径
 * @returns 本次渲染使用的 router 与 queryClient
 */
function renderApp(initialPath: string) {
  setAccessToken("access-token");
  const queryClient = createQueryClient();
  const router = createAppRouter({
    queryClient,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
  });
  render(<AppProviders router={router} queryClient={queryClient} />);
  return { router, queryClient };
}

/*
 * AC-3（游客重定向登录页、非管理员渲染 403 屏）由既有 auth-flow 用例真实覆盖，这里不重复实现：
 * - src/features/auth/__tests__/auth-flow.test.tsx
 *   "redirects unauthenticated visitors from admin routes to login after refresh fails"
 * - src/features/auth/__tests__/auth-flow.test.tsx
 *   "renders the 403 screen for a signed-in non-admin opening admin routes"
 * 管理区并入壳层（adminGateRoute 重挂到 shellRoute 之下）后两例均保持通过；403 例已补预置
 * 已定级状态（壳层定级门在管理门之前生效），见该文件内注释。
 */
describe("admin shell", () => {
  beforeEach(() => {
    resetApiState();
    // jsdom 没有 IndexedDB；注入内存存储让 local-engine 的定级门与读写正常工作。
    setEngineStorageForTests(createMemoryEngineStorage());
    window.localStorage.clear();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("AC-1: 管理员打开 /admin 时壳层主导航含「管理」项且处于激活态", async () => {
    markPlaced();
    vi.stubGlobal("fetch", adminFetchMock());

    const { router } = renderApp("/admin");

    // 管理页头出现，证明 /admin 真正渲染而不是被定级门或登录页重定向。
    expect(await screen.findByRole("heading", { name: /^管理/ })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/admin");
    // 管理入口与 今日/复习/统计 同级：在主导航里且按 /admin 前缀判定为激活。
    const mainNav = screen.getByRole("navigation", { name: "主导航" });
    const adminLink = within(mainNav).getByRole("link", { name: "管理" });
    expect(adminLink).toHaveAttribute("aria-current", "page");
  });

  it("AC-2: 管理员打开 /admin 时页面渲染在壳层内且不再自带 .page/.view 外壳", async () => {
    markPlaced();
    vi.stubGlobal("fetch", adminFetchMock());

    renderApp("/admin");

    // 壳层顶栏与管理页头同屏：管理页渲染在 AppShell 内容区里。
    expect(await screen.findByRole("heading", { name: /^管理/ })).toBeInTheDocument();
    expect(document.querySelector(".topbar")).toBeInTheDocument();
    // AdminLayout 已拆除自带框架：document 内 .page/.view 只剩壳层提供的一层，
    // 不再出现旧实现「壳层 .view 里再嵌一层 .page>.view」的重复几何。
    expect(document.querySelectorAll(".page")).toHaveLength(1);
    expect(document.querySelectorAll(".view")).toHaveLength(1);
  });

  it("AC-4: 管理员点击「用户」页签后 URL 切 ?tab=users、内容切换且壳层顶栏保持可见", async () => {
    markPlaced();
    vi.stubGlobal("fetch", adminFetchMock());

    const { router } = renderApp("/admin");

    // 缺省页签是概览：等概览指标渲染出来，作为「内容切换」的前像。
    expect(await screen.findByText("用户总数")).toBeInTheDocument();

    const user = userEvent.setup();
    const adminNav = screen.getByRole("navigation", { name: "管理导航" });
    await user.click(within(adminNav).getByRole("link", { name: "用户" }));

    // 单页机制：页签不产生独立路由，只切换 /admin 的 tab search 参数。
    await waitFor(() => expect(router.state.location.pathname).toBe("/admin"));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: "users" }));
    // 用户页签内容出现（用户行来自 /admin/users 桩数据），概览内容随页签卸载。
    expect(await screen.findByText("demo-user")).toBeInTheDocument();
    expect(screen.queryByText("用户总数")).not.toBeInTheDocument();
    // 页签切换发生在壳层内：顶栏自始至终可见。
    expect(document.querySelector(".topbar")).toBeInTheDocument();
  });

  it("AC-1: 管理员打开 /admin 不再出现全局演示 Notice（核心页签已接真实接口）", async () => {
    markPlaced();
    vi.stubGlobal("fetch", adminFetchMock());

    renderApp("/admin");

    expect(await screen.findByRole("heading", { name: /^管理/ })).toBeInTheDocument();
    // 演示 Notice 已随 011 切片撤场；出题/模型/运维页签各自的页内演示横幅仍然足够提示。
    expect(screen.queryByText(/演示数据模式/)).not.toBeInTheDocument();
    expect(screen.queryByText(/VITE_USE_MOCK=true 启动体验完整管理端/)).not.toBeInTheDocument();
  });

  it("AC-5: 题库页签回收库带「已回收」标注、编辑弹窗预填并提交 PATCH", async () => {
    markPlaced();
    const patchBodies: Array<Record<string, unknown>> = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/api/auth/me")) {
          return jsonResponse(successEnvelope(ADMIN));
        }
        if (url.endsWith("/api/me/settings")) {
          return jsonResponse(successEnvelope(SETTINGS));
        }
        if (url.endsWith("/api/practice/today")) {
          return jsonResponse(successEnvelope(TODAY));
        }
        if (/\/api\/admin\/banks\/\d+\/sentences/.test(url)) {
          return jsonResponse(successEnvelope(BANK_SENTENCES_PAGE));
        }
        if (/\/api\/admin\/banks\/\d+$/.test(url)) {
          expect(init?.method).toBe("PATCH");
          patchBodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
          return jsonResponse(successEnvelope({ ...BANKS_PAGE.items[0], description: "新说明" }));
        }
        if (url.includes("/api/admin/banks")) {
          return jsonResponse(successEnvelope(BANKS_PAGE));
        }
        throw new Error(`unexpected request: ${url}`);
      }),
    );

    const { router } = renderApp("/admin?tab=banks");

    // C 裁决：mock 的 GET /admin/banks 缺省不过滤状态——回收库仍在缺省列表出现，行内标注「已回收」。
    expect(await screen.findByText("回收站里的库")).toBeInTheDocument();
    expect(screen.getByText("已回收")).toBeInTheDocument();

    // B：抽屉新增「编辑」入口 → 表单按当前值预填 → 保存提交 PATCH /api/admin/banks/{id}。
    const user = userEvent.setup();
    await user.click(screen.getAllByRole("button", { name: "管理" })[0]);
    // Drawer 的 role="dialog" 无 aria-label，可访问名不可用；用抽屉内的 h2 标题定位。
    const drawer = await screen.findByRole("dialog");
    expect(within(drawer).getByRole("heading", { name: "编辑目标库" })).toBeInTheDocument();
    await user.click(within(drawer).getByRole("button", { name: "编辑" }));

    const nameInput = await screen.findByLabelText("题库名称");
    expect(nameInput).toHaveValue("编辑目标库");
    await user.type(screen.getByLabelText("题库说明"), "新说明");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(patchBodies).toHaveLength(1));
    expect(patchBodies[0]).toEqual({
      name: "编辑目标库",
      description: "新说明",
      visibility: "PRIVATE",
    });
    // 保存成功后编辑弹窗关闭、列表缓存失效后重新拉取（fetch 桩继续应答即证明）。
    await waitFor(() => expect(screen.queryByLabelText("题库名称")).not.toBeInTheDocument());
    expect(router.state.location.pathname).toBe("/admin");
  });
});
