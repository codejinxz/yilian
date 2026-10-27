import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { meQueryOptions } from "@/lib/query";

/**
 * 403 无权限屏：view-denied——.auth 窄栏居中文字、kicker + 衬线标题 + 身份说明 + 两个出口。
 * 管理门在已登录但角色不是管理员时渲染本页；游客也能直达本页文案里的登录入口。
 * 权限在服务端，前端隐藏入口不算权限；这里只解释「为什么进不去」。
 */
export function ForbiddenPage() {
  const me = useQuery(meQueryOptions);
  // 管理门保证渲染本页时 me 已确认；查询失败时按游客口径兜底，不阻塞 403 屏。
  const roleText = me.data ? (me.data.role === "ADMIN" ? "管理员" : "普通用户") : "游客";

  return (
    // ：.auth（360px、4vh 起始）+ text-align:center，按钮排一行居中。
    <main className="relative z-10 mx-auto w-full max-w-[360px] px-6 pb-16 pt-[4vh] text-center">
      {/* .drawer-kicker：9px mono、0.18em 字距、ink3、10px 下距 */}
      <p className="mb-2.5 font-mono text-[9px] tracking-[0.18em] text-ink-faint">403 · 无权访问</p>
      {/* .auth h1：32px 衬线、10px 下距 */}
      <h1 className="mb-[10px] font-serif text-[32px] font-medium text-ink">
        这个页面只对管理员开放
      </h1>
      {/* .auth > p：13px ink2、1.75 行高、30px 下距 */}
      <p className="mb-[30px] text-[13px] leading-[1.75] text-ink-muted">
        当前身份：{roleText}。管理员入口在登录页。
      </p>

      {/* 内联按钮行：26px 上距、14px 间距、居中；.btn ghost / .btn primary 形态 */}
      <div className="mt-[26px] flex flex-wrap items-center justify-center gap-3.5">
        <Link
          to="/today"
          className="inline-flex cursor-pointer items-center justify-center rounded-[2px] border border-rule px-[22px] py-[11px] text-[13px] tracking-[0.06em] text-ink-muted transition-colors duration-200 hover:border-ink-faint hover:text-ink"
        >
          返回今日
        </Link>
        {me.data ? null : (
          <Link
            to="/login"
            className="inline-flex cursor-pointer items-center justify-center rounded-[2px] border border-vermilion bg-vermilion px-[22px] py-[11px] text-[13px] tracking-[0.06em] text-white transition-colors duration-200 hover:border-vermilion-deep hover:bg-vermilion-deep"
          >
            去登录
          </Link>
        )}
      </div>
    </main>
  );
}
