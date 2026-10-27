import type { QueryClient } from "@tanstack/react-query";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  type ErrorComponentProps,
  Link,
  Outlet,
  type RouterHistory,
  redirect,
} from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { AdminLayout } from "@/features/admin/AdminLayout";
import { ADMIN_URL_TABS, type AdminTabParam } from "@/features/admin/constants";
import { clearSession, onSessionCleared } from "@/features/auth/clear-session";
import { ForbiddenPage } from "@/features/auth/ForbiddenPage";
import { LoginPage, RegisterPage } from "@/features/auth/LoginPage";
import { PlacementPage } from "@/features/placement/PlacementPage";
import { PracticePage } from "@/features/practice/PracticePage";
import { ReviewPage } from "@/features/review/ReviewPage";
import { SettingsPage } from "@/features/settings/SettingsPage";
import { AppShell } from "@/features/shell/AppShell";
import { StatsPage } from "@/features/stats/StatsPage";
import { TodayPage } from "@/features/today/TodayPage";
import { ApiError, getAccessToken, setUnauthorizedHandler } from "@/lib/api";
import { loadPlacementState } from "@/lib/local-engine";
import { createQueryClient, meQueryOptions, queryKeys } from "@/lib/query";

/** 路由上下文只放 QueryClient；beforeLoad 通过它复用同一份查询缓存。 */
export interface RouterContext {
  queryClient: QueryClient;
}

/** 根布局只提供子路由出口，鉴权逻辑放在各路由的 beforeLoad。 */
function RootLayout() {
  return <Outlet />;
}

/** 会话查询进行中时的占位页，避免直接闪出登录页。 */
function RoutePending() {
  return (
    <main className="flex min-h-screen items-center justify-center px-6">
      <p className="text-sm text-ink-muted">正在确认登录状态…</p>
    </main>
  );
}

/**
 * 路由级错误页：优先展示 ApiError 的提示，其他错误给通用文案；重试时先失效当前用户查询再重置路由错误。
 * @param props 路由错误组件属性；error 为原始错误，reset 为重置回调
 */
function RouteError({ error, reset }: ErrorComponentProps) {
  const queryClient = useQueryClient();
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6">
      <h1 className="font-serif text-3xl">页面暂时打不开</h1>
      <p className="mt-3 text-sm leading-6 text-ink-muted">
        {error instanceof ApiError ? error.message : "网络或服务异常，请稍后重试。"}
      </p>
      <Button
        className="mt-6 self-start"
        onClick={() => {
          void queryClient.invalidateQueries({ queryKey: queryKeys.me });
          reset();
        }}
      >
        重试
      </Button>
    </main>
  );
}

/** 未匹配路由的兜底页，引导用户回到今日。 */
function NotFoundPage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6">
      <h1 className="font-serif text-3xl">页面不存在</h1>
      <Link to="/" className="mt-4 text-sm text-vermilion underline underline-offset-4">
        回到今日
      </Link>
    </main>
  );
}

/**
 * 管理端门组件：登录后角色不是管理员时渲染 403 屏，其余情况放行到子路由。
 * beforeLoad 已确保 me 查询成功，这里只是按缓存里的角色分流；真正的权限边界仍在服务端。
 */
function AdminGate() {
  const me = useQuery(meQueryOptions);
  if (me.data && me.data.role !== "ADMIN") {
    return <ForbiddenPage />;
  }
  return <Outlet />;
}

/**
 * 判断当前路径是否必须登录才能停留。
 * 只有 /admin/** 需要登录；游客可浏览路径（今日/练习/复习/统计/设置/定级/登录/注册）
 * 收到 401 时只清会话，不打断浏览——页面自身会以错误态或游客态呈现。
 * @param pathname 路由路径
 * @returns 是否属于登录后才能使用的区域
 */
function isLoginRequiredPath(pathname: string): boolean {
  return pathname.startsWith("/admin");
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  notFoundComponent: NotFoundPage,
});

/**
 * 壳层布局路由：游客即可进入（本地优先，不登录也能练）。
 * 登录/注册/定级与全部用户页面一样在壳层内渲染（三页都有完整顶栏 + view + 页脚）；
 * 进入前检查本地定级状态——未定级（placed !== true）一律先去 /placement，
 * 定级门显式放行 /placement 自身，因此首次进入不会在定级页上死循环。
 * 用 pathless（只声明 id、不声明 path）挂载，不产生独立 URL；管理页不并入壳层（AdminLayout 自带框架）。
 */
const shellRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "shell",
  component: AppShell,
  pendingComponent: RoutePending,
  errorComponent: RouteError,
  beforeLoad: ({ location }) => {
    // 定级状态存在本地（local-engine），同步读取即可，不引入异步等待；
    // 存储异常时按「未定级」降级——去定级页拿重试态，而不是让整个壳层报错。
    let placement: ReturnType<typeof loadPlacementState> = null;
    try {
      placement = loadPlacementState();
    } catch {
      placement = null;
    }
    if (placement?.placed !== true && location.pathname !== "/placement") {
      throw redirect({ to: "/placement" });
    }
  },
});

/**
 * 登录路由：公开，渲染在壳层内（顶栏、页脚与原型一致）。
 * 已持有 access token 且能够确认用户时跳首页；未登录或 token 失效时留在登录页。
 * 注意 createRoute 会立刻执行 getParentRoute，因此本定义必须位于 shellRoute 之后。
 */
const loginRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "/login",
  component: LoginPage,
  beforeLoad: async ({ context }) => {
    // 已持有 access token 时直接回首页；冷启动无 token 时保留登录页。
    if (!getAccessToken()) {
      return;
    }
    try {
      await context.queryClient.ensureQueryData(meQueryOptions);
      throw redirect({ to: "/" });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        return;
      }
      throw error;
    }
  },
});

/**
 * 注册路由：与登录页共用同一套表单（register 初态）；保留独立 URL 让旧链接与测试不断。
 */
const registerRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "/register",
  component: RegisterPage,
  beforeLoad: async ({ context }) => {
    // 策略与登录页一致：已持有 access token 时确认用户后直接回首页。
    if (!getAccessToken()) {
      return;
    }
    try {
      await context.queryClient.ensureQueryData(meQueryOptions);
      throw redirect({ to: "/" });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        return;
      }
      throw error;
    }
  },
});

/**
 * 三题定级路由：渲染在壳层内（完整顶栏 + view + 页脚），
 * 壳层定级门显式放行本页，未定级的首次访客在这里完成三题或跳过。
 */
const placementRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "/placement",
  component: PlacementPage,
  pendingComponent: RoutePending,
  errorComponent: RouteError,
});

/** 首页 index 路由：今日启动台就是首页，登录游客与登录用户都直接落在 /。 */
const indexRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "/",
  component: TodayPage,
});

/** 今日启动台：与 / 同内容；保留独立路径，方便从其他页面一键回到今日。 */
const todayRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "today",
  component: TodayPage,
});

/**
 * 练习路由：查询串里的 attemptId 指向已提交的作答（回看批改结果），缺省时拉新题；
 * sentenceId + mode 由复习页/今日页「开始/重练」跳转带入，指定练习这一句；
 * bankId 由今日页题库 chips 带入，限定在该题库内取题；
 * errors 由今日页「错题」chip 带入，只在错题里取题（的错题集入口）。
 * 在这里声明 search 的形状，提交后的回跳与「开始新题」的清参跳转才能通过类型化导航的检查。
 */
const practiceRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "practice",
  component: PracticePage,
  validateSearch: (
    search: Record<string, unknown>,
  ): {
    attemptId?: string;
    sentenceId?: string;
    mode?: "CLOZE" | "FREE";
    bankId?: string;
    errors?: true;
  } => {
    const attemptId = search.attemptId;
    const sentenceId = search.sentenceId;
    const mode = search.mode;
    const bankId = search.bankId;
    return {
      ...(typeof attemptId === "string" && attemptId.length > 0 ? { attemptId } : {}),
      ...(typeof sentenceId === "string" && sentenceId.length > 0 ? { sentenceId } : {}),
      ...(mode === "CLOZE" || mode === "FREE" ? { mode } : {}),
      ...(typeof bankId === "string" && bankId.length > 0 ? { bankId } : {}),
      ...(search.errors === "1" || search.errors === true ? { errors: true as const } : {}),
    };
  },
});

/**
 * 复习路由：filter 由练习页「{n} 句到期」chip 带入，直达对应筛选档（dueChip）；
 * 非法值按无筛选处理，不产出脏参数。
 */
const reviewRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "review",
  component: ReviewPage,
  validateSearch: (
    search: Record<string, unknown>,
  ): { filter?: "due" | "learning" | "mastered" } => {
    const filter = search.filter;
    return filter === "due" || filter === "learning" || filter === "mastered" ? { filter } : {};
  },
});

/** 练习统计：正确率与错误标签回顾。 */
const statsRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "stats",
  component: StatsPage,
});

/** 学习设置：主题、每日目标等个人配置。 */
const settingsRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "settings",
  component: SettingsPage,
});

/**
 * 管理端门路由：进入前必须能确认当前用户——me 查询失败（未登录、token 失效或后端不可用）
 * 一律按未登录处理重定向登录页，管理门不崩溃也不放行；角色分流由 AdminGate 组件完成。
 */
const adminGateRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "admin",
  component: AdminGate,
  pendingComponent: RoutePending,
  errorComponent: RouteError,
  beforeLoad: async ({ context }) => {
    try {
      await context.queryClient.ensureQueryData(meQueryOptions);
    } catch {
      // 权限在服务端；前端这里只负责把未确认身份的访问挡在登录页之外。
      throw redirect({ to: "/login" });
    }
  },
});

/**
 * 管理端唯一路由（#/admin 单页 + ?tab= 页签参数，不产生独立子路由）。
 * 页头九页签由 AdminLayout 切换本路由的 search 并按 tab 条件渲染对应组件；
 * validateSearch 只放行八个非缺省页签，概览是缺省页（/admin 不带参数），非法值丢弃落回概览。
 */
const adminRoute = createRoute({
  getParentRoute: () => adminGateRoute,
  path: "/admin",
  component: AdminLayout,
  validateSearch: (search: Record<string, unknown>): { tab?: AdminTabParam } => {
    const tab = search.tab;
    const matched = ADMIN_URL_TABS.find((item) => item === tab);
    return matched ? { tab: matched } : {};
  },
});

/**
 * 完整路由树。壳层包住全部用户页面（登录/注册/定级 + 今日/练习/复习/统计/设置），
 * 管理区（/admin）单独挂在管理门之下：壳层布局包住用户页面，管理门包住管理页面。
 * 管理端是单页九页签（页签即 /admin 的 search 参数），导入是题库页签内的一段，不再有独立路由。
 */
export const routeTree = rootRoute.addChildren([
  shellRoute.addChildren([
    loginRoute,
    registerRoute,
    placementRoute,
    indexRoute,
    todayRoute,
    practiceRoute,
    reviewRoute,
    statsRoute,
    settingsRoute,
  ]),
  adminGateRoute.addChildren([adminRoute]),
]);

/**
 * 创建应用路由并注册全局 401 处理：清空内存 token、会话状态与用户缓存；
 * 只有停留在 /admin/** 这类必须登录的区域时才跳登录页，游客可浏览路径不打断。
 * @param options QueryClient 与可选 history（测试用 memory history）
 * @returns 已注册鉴权副作用的路由实例
 */
export function createAppRouter(options: { queryClient: QueryClient; history?: RouterHistory }) {
  const router = createRouter({
    routeTree,
    context: { queryClient: options.queryClient },
    history: options.history,
    defaultPreload: "intent",
    scrollRestoration: true,
  });

  setUnauthorizedHandler(() => {
    clearSession(options.queryClient);
    if (isLoginRequiredPath(router.state.location.pathname)) {
      void router.navigate({ to: "/login", replace: true });
    }
  });

  onSessionCleared(() => {
    clearSession(options.queryClient, false);
    if (isLoginRequiredPath(router.state.location.pathname)) {
      void router.navigate({ to: "/login", replace: true });
    }
  });

  return router;
}

/** 应用单例 QueryClient，供路由与入口共享同一份缓存。 */
export const queryClient = createQueryClient();
/** 浏览器环境的单例 router；测试应调用 createAppRouter 注入 memory history。 */
export const router = createAppRouter({ queryClient });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
