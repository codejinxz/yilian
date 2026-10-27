import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { Pagination } from "@/components/pagination";
import { getErrorLogDetail, getErrorLogs } from "@/lib/api/admin";
import { queryKeys } from "@/lib/query";
import { relativeTimeLabel, TIME_RANGE_OPTIONS, type TimeRangeValue } from "./constants";
import { timeRangeParams } from "./labels";
import { AdminSearch, AdminSelect, Drawer, EmptyRow, Row, RowAct, RowList, RowText } from "./ui";

/** 异常模块选项；与 errorModuleSchema 的取值一致。 */
const MODULE_OPTIONS = [
  { value: "all", label: "全部模块" },
  { value: "practice", label: "practice" },
  { value: "content", label: "content" },
  { value: "ai", label: "ai" },
  { value: "account", label: "account" },
  { value: "admin", label: "admin" },
  { value: "analytics", label: "analytics" },
  { value: "system", label: "system" },
] as const;

/**
 * 异常日志页签：搜索、模块与时间筛选、行级列表与堆栈抽屉。
 * 页脚固定保留期说明；列表不含堆栈，堆栈只在详情抽屉出现。
 */
export function ErrorsPage() {
  const [keyword, setKeyword] = useState("");
  const [module, setModule] = useState("all");
  const [range, setRange] = useState<TimeRangeValue>("all");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [detailId, setDetailId] = useState<string | null>(null);

  const logsQuery = useQuery({
    queryKey: queryKeys.admin.errorLogs({ page, size, keyword, module, range }),
    queryFn: ({ signal }) => {
      const rangeParams = timeRangeParams(range);
      return getErrorLogs(
        {
          page,
          size,
          ...(keyword.trim() === "" ? {} : { keyword: keyword.trim() }),
          ...(module === "all" ? {} : { module }),
          ...rangeParams,
        },
        signal,
      );
    },
  });
  const detailQuery = useQuery({
    queryKey: queryKeys.admin.errorLog(detailId ?? ""),
    queryFn: ({ signal }) => getErrorLogDetail(detailId ?? "", signal),
    enabled: detailId !== null,
  });

  return (
    <div>
      <div className="mb-2.5 flex flex-wrap items-center gap-3.5">
        <AdminSearch
          aria-label="搜索异常类、消息或 traceId"
          placeholder="搜索异常类、消息或 traceId"
          value={keyword}
          onChange={(event) => {
            setKeyword(event.target.value);
            setPage(1);
          }}
        />
        <AdminSelect
          aria-label="模块筛选"
          value={module}
          onChange={(event) => {
            setModule(event.target.value);
            setPage(1);
          }}
        >
          {MODULE_OPTIONS.map((option) => (
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
        <LoadingBlock label="正在载入异常日志…" />
      ) : logsQuery.isError ? (
        <ErrorState error={logsQuery.error} onRetry={() => void logsQuery.refetch()} />
      ) : (
        <>
          <RowList>
            {logsQuery.data.items.length === 0 ? (
              <EmptyRow>没有匹配的异常。</EmptyRow>
            ) : (
              logsQuery.data.items.map((log) => (
                <Row key={log.id}>
                  <RowText
                    main={`${log.exceptionClass} · ${log.message ?? "—"}`}
                    meta={`${log.module} · ${log.clientIp ?? "—"} · ${log.traceId} · ${log.operator ?? "—"}`}
                  />
                  <span className="whitespace-nowrap text-right font-mono text-[11px] text-ink-muted">
                    {relativeTimeLabel(log.createdAt)}
                  </span>
                  <RowAct onClick={() => setDetailId(log.id)}>查看</RowAct>
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

      <Drawer
        open={detailId !== null}
        onClose={() => setDetailId(null)}
        kicker={`异常 · ${detailQuery.data?.traceId ?? ""}`}
        title={detailQuery.data?.exceptionClass ?? "加载中"}
        meta={
          detailQuery.data
            ? `${detailQuery.data.module} · ${detailQuery.data.operator ?? "—"} · ${detailQuery.data.clientIp ?? "—"} · ${relativeTimeLabel(detailQuery.data.createdAt)}`
            : undefined
        }
      >
        {detailQuery.isPending ? (
          <LoadingBlock label="正在载入堆栈…" rows={2} />
        ) : detailQuery.isError ? (
          <ErrorState
            compact
            error={detailQuery.error}
            onRetry={() => void detailQuery.refetch()}
          />
        ) : (
          <>
            <p className="section-title mt-[30px]">消息</p>
            <p className="text-[13px] leading-[1.7] text-ink-muted">
              {detailQuery.data?.message ?? "—"}
            </p>
            <p className="section-title mt-[30px]">堆栈</p>
            <pre className="mt-3.5 max-h-80 overflow-auto whitespace-pre-wrap break-words border border-rule bg-surface p-3.5 font-mono text-[11px] leading-[1.7] text-ink-muted">
              {detailQuery.data?.stackTrace ?? "—"}
            </pre>
          </>
        )}
      </Drawer>

      <p className="mt-5 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
        异常日志默认保留 30 天；堆栈只进后台。
      </p>
    </div>
  );
}
