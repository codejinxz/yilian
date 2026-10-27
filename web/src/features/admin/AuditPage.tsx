import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { Pagination } from "@/components/pagination";
import { getAuditLogs } from "@/lib/api/admin";
import { queryKeys } from "@/lib/query";
import {
  ACTION_FILTER_OPTIONS,
  actionLabel,
  relativeTimeLabel,
  TIME_RANGE_OPTIONS,
  type TimeRangeValue,
} from "./constants";
import { timeRangeParams } from "./labels";
import { AdminSearch, AdminSelect, EmptyRow, Row, RowList, RowText } from "./ui";

/**
 * 操作日志页签：搜索、操作筛选、时间筛选与分页。
 * 页脚固定保留期说明；数据走 mock audit-logs，管理端的每个写操作都会追加到这里。
 */
export function AuditPage() {
  const [keyword, setKeyword] = useState("");
  const [action, setAction] = useState("all");
  const [range, setRange] = useState<TimeRangeValue>("all");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const logsQuery = useQuery({
    queryKey: queryKeys.admin.auditLogs({ page, size, keyword, action, range }),
    queryFn: ({ signal }) => {
      const rangeParams = timeRangeParams(range);
      return getAuditLogs(
        {
          page,
          size,
          ...(keyword.trim() === "" ? {} : { keyword: keyword.trim() }),
          ...(action === "all" ? {} : { action }),
          ...rangeParams,
        },
        signal,
      );
    },
  });

  return (
    <div>
      <div className="mb-2.5 flex flex-wrap items-center gap-3.5">
        <AdminSearch
          aria-label="搜索操作、对象或操作者"
          placeholder="搜索操作、对象或操作者"
          value={keyword}
          onChange={(event) => {
            setKeyword(event.target.value);
            setPage(1);
          }}
        />
        <AdminSelect
          aria-label="操作筛选"
          value={action}
          onChange={(event) => {
            setAction(event.target.value);
            setPage(1);
          }}
        >
          {ACTION_FILTER_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </AdminSelect>
        <AdminSelect
          aria-label="时间筛选"
          value={range}
          onChange={(event) => {
            setRange(event.target.value as TimeRangeValue);
            setPage(1);
          }}
        >
          {TIME_RANGE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </AdminSelect>
      </div>

      {logsQuery.isPending ? (
        <LoadingBlock label="正在载入操作日志…" />
      ) : logsQuery.isError ? (
        <ErrorState error={logsQuery.error} onRetry={() => void logsQuery.refetch()} />
      ) : (
        <>
          <RowList>
            {logsQuery.data.items.length === 0 ? (
              <EmptyRow>没有匹配的记录。</EmptyRow>
            ) : (
              logsQuery.data.items.map((log) => (
                <Row key={log.id}>
                  {/* 裸 targetId（UUID）不可读：优先展示 targetLabel，缺失时退回对象类型，绝不显示裸 id。 */}
                  <RowText
                    main={actionLabel(log.action)}
                    meta={`${log.actorName} · ${log.clientIp ?? "—"} · ${log.targetLabel ?? log.targetType ?? "—"}${log.reason ? ` · ${log.reason}` : ""}`}
                  />
                  <span className="whitespace-nowrap text-right font-mono text-[11px] text-ink-muted">
                    {relativeTimeLabel(log.createdAt)}
                  </span>
                  <span aria-hidden="true" />
                </Row>
              ))
            )}
          </RowList>
          <Pagination
            page={logsQuery.data.page}
            size={logsQuery.data.size}
            total={logsQuery.data.total}
            totalPages={logsQuery.data.totalPages}
            hasNext={logsQuery.data.hasNext}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(1);
            }}
          />
        </>
      )}

      <p className="mt-5 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
        审计日志保留 1 年；答题流水只留 90 天，聚合统计长期保留。查看原文必须填原因。
      </p>
    </div>
  );
}
