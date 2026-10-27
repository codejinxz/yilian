import { Link, useSearch } from "@tanstack/react-router";
import { Notice } from "@/components/feedback";
import { isMockEnabled } from "@/lib/mock-flag";
import { AuditPage } from "./AuditPage";
import { BanksPage } from "./BanksPage";
import { ADMIN_TABS, type AdminTabId, type AdminTabParam, adminTabSearch } from "./constants";
import { ErrorsPage } from "./ErrorsPage";
import { GenerationPage } from "./GenerationPage";
import { ModelsPage } from "./ModelsPage";
import { OpsPage } from "./OpsPage";
import { OverviewPage } from "./OverviewPage";
import { SentencesPage } from "./SentencesPage";
import { UsersPage } from "./UsersPage";

/**
 * 管理端布局：原型 .page > .view 壳 + 页头（衬线标题 + ADMIN mono 徽标）+ 九页签导航。
 * #/admin 单页机制：页签不产生独立路由，而是切换本路由的 ?tab= 参数；
 * 内容区按当前页签条件渲染九个页签组件，缺省渲染概览。
 * 管理页不并入 AppShell，这里自带同参壳层；服务端仍会独立鉴权，这里只负责导航与预期说明。
 */
export function AdminLayout() {
  // 管理端数据依赖 mock 演示层；真实模式请求会失败，各页已有错误态兜底，这里只做预期说明。
  const showDemoNotice = !isMockEnabled();
  // 页签状态就在本路由的 search 里；非法值已被 validateSearch 丢弃，缺省即概览。
  const search = useSearch({ strict: false }) as { tab?: AdminTabParam };
  const activeTab: AdminTabId = search.tab ?? "overview";

  return (
    <main className="page">
      <div className="view">
        <h1 className="page-title">
          管理
          <span className="ml-2 inline-block rounded-sm border border-vermilion/40 px-[6px] py-[3px] align-[6px] font-mono text-[9px] tracking-[0.16em] text-vermilion">
            ADMIN
          </span>
        </h1>
        <p className="page-sub">用户、题库、出题、模型、日志。单人运营，只看该看的。</p>

        {showDemoNotice ? (
          <Notice className="-mt-6 mb-9">
            管理端数据接口属后续切片，当前为演示数据模式；请以 VITE_USE_MOCK=true
            启动体验完整管理端。
          </Notice>
        ) : null}

        <nav
          aria-label="管理导航"
          className="flex gap-6 overflow-x-auto border-b border-rule [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        >
          {ADMIN_TABS.map((item) => {
            // 激活态由当前 search.tab 判定：九个页签都指向 /admin，路由的路径激活判定无法区分它们。
            const active = item.id === activeTab;
            return (
              <Link
                key={item.id}
                to="/admin"
                search={adminTabSearch(item.id)}
                aria-current={active ? "page" : undefined}
                className={`mb-[-1px] whitespace-nowrap border-b border-transparent pb-2.5 text-xs tracking-[0.06em] text-ink-faint transition-colors hover:text-ink-muted${active ? " border-vermilion text-ink" : ""}`}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div className="mt-9">
          {activeTab === "overview" && <OverviewPage />}
          {activeTab === "users" && <UsersPage />}
          {activeTab === "sentences" && <SentencesPage />}
          {activeTab === "banks" && <BanksPage />}
          {activeTab === "generation" && <GenerationPage />}
          {activeTab === "models" && <ModelsPage />}
          {activeTab === "audit" && <AuditPage />}
          {activeTab === "errors" && <ErrorsPage />}
          {activeTab === "ops" && <OpsPage />}
        </div>
      </div>
    </main>
  );
}
