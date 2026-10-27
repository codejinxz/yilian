import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { getAdminOverview } from "@/lib/api/admin";
import { relativeTimeLabel } from "./constants";
import { AdminSection, Bars, BarsCaption, EmptyRow, MetricRow, Row, RowList, RowText } from "./ui";

/** 概览页的权限边界三行；与原型 permList 一致，是展示性约定而非权限实现。 */
const PERMISSION_ROWS: Array<{ role: string; scope: string }> = [
  { role: "用户", scope: "练习、复习、统计、设置、自己的记录" },
  { role: "管理员", scope: "以上全部 + 用户管理、题库导入、模型配置、审计日志" },
  { role: "任何人都不行", scope: "查看密码、模拟登录、修改成绩" },
];

/**
 * 待处理事项的跳转目标页签：mock 返回的 href 只是演示说明，前端按 id 映射到本页页签；
 * 管理端是单页，「处理」通过 /admin 的 tab 参数原位切换页签。未识别的 id 留在概览。
 */
const TODO_TARGETS = {
  imports: "generation",
  disabled: "users",
  appeals: "users",
} as const;

/**
 * 管理端概览：四个指标、近 7 天活跃、待处理、最近活动与权限边界。
 * 数据来自 mock 聚合端点 /admin/overview；待处理的「处理」按钮跳到对应页签。
 */
export function OverviewPage() {
  const overview = useQuery({
    queryKey: ["admin", "overview"],
    queryFn: ({ signal }) => getAdminOverview(signal),
  });

  if (overview.isPending) {
    return <LoadingBlock label="正在汇总运营数据…" />;
  }
  if (overview.isError) {
    return <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />;
  }

  const data = overview.data;
  const barTitles = data.active7d.map((day) => `${day.date.slice(5)} · ${day.count} 次作答`);

  return (
    <div>
      <MetricRow
        items={[
          { value: data.metrics.totalUsers, label: "用户总数" },
          { value: data.metrics.activeToday, label: "今日活跃" },
          {
            value: (
              <>
                {data.metrics.totalAttempts.toLocaleString()}
                <small className="ml-1 font-mono text-xs text-ink-faint">句</small>
              </>
            ),
            label: "累计答题",
          },
          {
            value: (
              <>
                ¥{data.metrics.estimatedCost.toFixed(1)}
                <small className="ml-1 font-mono text-xs text-ink-faint">估算</small>
              </>
            ),
            label: "模型成本估算",
          },
        ]}
      />

      <AdminSection title="近 7 天活跃">
        <Bars values={data.active7d.map((day) => day.count)} titles={barTitles} />
        <BarsCaption left="7 天前" right="今天" />
      </AdminSection>

      <AdminSection title="待处理">
        <RowList>
          {data.todos.length === 0 ? (
            <EmptyRow>暂无待处理事项。</EmptyRow>
          ) : (
            data.todos.map((todo) => {
              const targetTab = TODO_TARGETS[todo.id as keyof typeof TODO_TARGETS];
              return (
                <Row key={todo.id}>
                  <RowText main={todo.title} meta={todo.meta} />
                  <span aria-hidden="true" />
                  <Link
                    to="/admin"
                    search={targetTab ? { tab: targetTab } : {}}
                    className="whitespace-nowrap text-xs tracking-[0.05em] text-ink-muted transition-colors hover:text-vermilion"
                  >
                    处理
                  </Link>
                </Row>
              );
            })
          )}
        </RowList>
      </AdminSection>

      <AdminSection title="最近活动">
        <RowList>
          {data.activity.length === 0 ? (
            <EmptyRow>还没有练习活动。</EmptyRow>
          ) : (
            data.activity.map((item) => (
              <Row key={item.id}>
                <RowText main={item.who} meta={item.what} />
                <span aria-hidden="true" />
                <span className="whitespace-nowrap text-right font-mono text-[11px] text-ink-muted">
                  {relativeTimeLabel(item.at)}
                </span>
              </Row>
            ))
          )}
        </RowList>
      </AdminSection>

      <AdminSection title="权限边界">
        <RowList>
          {PERMISSION_ROWS.map((row) => (
            <Row key={row.role}>
              <RowText main={row.role} meta={row.scope} />
              <span aria-hidden="true" />
            </Row>
          ))}
        </RowList>
      </AdminSection>
    </div>
  );
}
