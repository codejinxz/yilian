import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, Outlet, useRouterState } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { flushQueuedAttempts, useOfflineQueueCount } from "@/features/practice/offline-queue";
import { useSettingsStore } from "@/features/settings/store";
import { useKeyboardInset } from "@/hooks/use-keyboard-inset";
import { useOnlineStatus } from "@/hooks/use-online-status";
import { applyUpdate, registerServiceWorker, subscribePwaUpdate } from "@/lib/pwa";
import { meQueryOptions, queryKeys, settingsQueryOptions, todayQueryOptions } from "@/lib/query";

/** 顶部导航：与原型一致只留「今日 / 复习 / 统计」；练习入口在今日启动台的主按钮上。 */
const NAV_ITEMS = [
  { to: "/today", label: "今日" },
  { to: "/review", label: "复习" },
  { to: "/stats", label: "统计" },
] as const;

/** 移动端底部页签：单字衬线 + 小标签，与原型 tabbar 相同的三项。 */
const TAB_ITEMS = [
  { to: "/today", glyph: "今", label: "今日" },
  { to: "/review", glyph: "复", label: "复习" },
  { to: "/settings", glyph: "我", label: "我的" },
] as const;

/**
 * 手动判定导航激活态（原型 .on：墨色 + 朱红下边框）。
 * TanStack 的 activeProps 只做前缀匹配，覆盖不了原型规则：首页 / 与 /today 同为「今日」，
 * 练习页 /practice 也归入「今日」，管理入口按 /admin 前缀，其余页签按路径精确相等。
 * @param to 导航目标路径
 * @param pathname 当前路由路径
 * @returns 是否应显示激活态
 */
function isNavActive(to: string, pathname: string): boolean {
  switch (to) {
    case "/today":
      return pathname === "/" || pathname === "/today" || pathname === "/practice";
    case "/admin":
      return pathname.startsWith("/admin");
    case "/review":
    case "/stats":
    case "/settings":
      return pathname === to;
    default:
      return false;
  }
}

/**
 * 应用壳：顶部品牌与导航、连续天数、主题与头像入口、移动端三页签与系统条。
 * 壳层几何（.topbar/.page/.view/.foot/.sysbar/.tabbar）逐值，样式集中在 styles.css；
 * 壳层对游客开放：设置只在登录后拉取；今日数据始终启用——真实模式由本地适配层应答（游客也有数据），
 * mock 模式游客拿到的 401 被本组件静默吸收（只读 data，不渲染错误）。
 * 退出登录入口已移入设置页（顶栏只留头像 → 设置），会话控制不再出现在全局框架上。
 */
export function AppShell() {
  const queryClient = useQueryClient();
  const online = useOnlineStatus();
  const pendingAttempts = useOfflineQueueCount();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const me = useQuery(meQueryOptions);
  const user = me.data;
  const isAdmin = user?.role === "ADMIN";
  // 服务端设置只在登录后拉取；游客查询只会拿到 401，白白清空会话缓存。
  // 今日数据始终启用（见组件注释），与今日页共享同一份缓存，不多发请求。
  const settings = useQuery({ ...settingsQueryOptions, enabled: Boolean(user) });
  const today = useQuery(todayQueryOptions);
  const theme = useSettingsStore((state) => state.theme);
  const setTheme = useSettingsStore((state) => state.setTheme);
  const syncTheme = useSettingsStore((state) => state.syncTheme);
  const [queueMessage, setQueueMessage] = useState<string | null>(null);
  // 有新版本待用户确认生效时为 true；状态由 pwa.ts 的 SW 事件驱动。
  const [updateReady, setUpdateReady] = useState(false);

  useKeyboardInset();

  // SW 注册与更新订阅：先订阅再注册，避免注册期间已出现的待生效版本被错过。
  // 仅生产构建会真正注册（见 pwa.ts），dev 与测试环境两者都是空操作。
  useEffect(() => {
    const unsubscribe = subscribePwaUpdate(setUpdateReady);
    void registerServiceWorker();
    return unsubscribe;
  }, []);

  // 服务端设置是主题的权威来源；本地 Zustand 只负责首屏避免闪烁。
  useEffect(() => {
    if (settings.data) {
      syncTheme(settings.data.theme);
    }
  }, [settings.data, syncTheme]);

  // 联网后自动补交离线答卷；失败会保留在本地并给出提示，不静默丢弃。
  useEffect(() => {
    if (!online || pendingAttempts === 0) {
      return;
    }
    let cancelled = false;
    void flushQueuedAttempts().then((result) => {
      if (cancelled) {
        return;
      }
      if (result.synced > 0) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.today });
        setQueueMessage(`已同步 ${result.synced} 份离线答卷。`);
      }
      if (result.message) {
        setQueueMessage(result.message);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [online, pendingAttempts, queryClient]);

  const nextTheme = theme === "paper" ? "night" : "paper";
  // 游客在真实模式下读本地引擎的今日快照，能看到自己的连续天数；无数据时不显示，
  // 避免拿 0 天制造打卡压力（契约 §2：不做惩罚性打卡）。
  const streakDays = today.data?.streakDays ?? null;
  const avatarText = user ? ([...user.username][0] ?? "我") : "游";
  const avatarTitle = user
    ? `${user.username}${isAdmin ? " · 管理员" : " · 学习者"}`
    : "游客 · 打开设置";

  return (
    <>
      <header className="topbar">
        <Link to="/today" className="brand">
          译练 <span className="sub">YILIAN</span>
        </Link>

        <nav aria-label="主导航" className="nav">
          {NAV_ITEMS.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              className={isNavActive(item.to, pathname) ? "on" : undefined}
              aria-current={isNavActive(item.to, pathname) ? "page" : undefined}
            >
              {item.label}
            </Link>
          ))}
          {/* 管理入口只对管理员渲染；真正的权限校验在服务端（契约 §2.7）。 */}
          {isAdmin ? (
            <Link
              to="/admin"
              className={isNavActive("/admin", pathname) ? "on" : undefined}
              aria-current={isNavActive("/admin", pathname) ? "page" : undefined}
            >
              管理
            </Link>
          ) : null}
        </nav>

        <div className="meta">
          {/* 今日数据缺失时不显示连续天数，避免拿 0 天制造打卡压力（契约 §2：不做惩罚性打卡）。 */}
          {streakDays !== null ? <span className="streak">连续 {streakDays} 天</span> : null}
          <button
            type="button"
            onClick={() => setTheme(nextTheme)}
            className="icon"
            title={nextTheme === "night" ? "切换到夜读" : "切换到稿纸"}
            aria-label={nextTheme === "night" ? "切换到夜读" : "切换到稿纸"}
          >
            {theme === "paper" ? "☾" : "☀"}
          </button>
          {/* 登录态头像加 .on（朱红描边），updateAvatar 的 classList.toggle('on')。 */}
          <Link
            to="/settings"
            className={user ? "avatar on" : "avatar"}
            title={avatarTitle}
            aria-label="打开设置"
          >
            {avatarText}
          </Link>
        </div>
      </header>

      {/* 系统条（.sysbar）：离线、待生效版本、离线答卷同步结果。离线与同步提示互斥（同步只在联网后发生）。 */}
      {!online ? (
        <div role="status" className="sysbar">
          当前处于离线状态。题库与批改可能无法更新，填写内容会先保存在本机
          {pendingAttempts > 0 ? `（待同步 ${pendingAttempts} 份）` : ""}。
        </div>
      ) : null}
      {updateReady ? (
        <div role="status" className="sysbar">
          有新版本可用
          {/* 更新必须用户确认后才生效（红线：禁止静默 skipWaiting）；点击后向 SW 发消息并重载。 */}
          <button type="button" onClick={applyUpdate}>
            刷新
          </button>
        </div>
      ) : null}
      {queueMessage ? (
        <div role="status" className="sysbar">
          {queueMessage}
        </div>
      ) : null}

      {/* .page 提供左右与底部留白（含安全区与键盘高度），.view 提供居中 1000px 与顶部留白；
          各页面在 .view 内自管内部布局（练习页用 stage 网格，今日/定级用窄栏）。 */}
      <main className="page">
        <div className="view">
          <Outlet />
        </div>
        <footer className="foot">
          <span>⌘K 命令面板</span>
          <span aria-hidden="true">·</span>
          <span>译练</span>
          <span aria-hidden="true">·</span>
          <span>本地优先</span>
          <span aria-hidden="true">·</span>
          <span>一屏一句</span>
        </footer>
      </main>

      <nav aria-label="移动端导航" className="tabbar">
        {TAB_ITEMS.map((item) => (
          <Link
            key={item.to}
            to={item.to}
            className={isNavActive(item.to, pathname) ? "on" : undefined}
            aria-current={isNavActive(item.to, pathname) ? "page" : undefined}
          >
            <b>{item.glyph}</b>
            {item.label}
          </Link>
        ))}
      </nav>
    </>
  );
}
