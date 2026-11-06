import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { Pagination } from "@/components/pagination";
import { decideAdminAppeal, getAdminAppeals } from "@/lib/api/admin";
import { queryKeys } from "@/lib/query";
import { relativeTimeLabel } from "./constants";
import {
  AdminSelect,
  EmptyRow,
  ReasonDialog,
  Row,
  RowAct,
  RowList,
  RowText,
  StatePill,
  useToastMessage,
} from "./ui";

/** 状态筛选下拉选项；取值与服务端 AdminAppealServiceImpl 的合法集合一致。 */
const STATUS_FILTER_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "all", label: "全部状态" },
  { value: "PENDING", label: "待终审" },
  { value: "ACCEPTED", label: "已通过" },
  { value: "REJECTED", label: "已驳回" },
];

/** 类型筛选下拉选项。 */
const KIND_FILTER_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "all", label: "全部类型" },
  { value: "APPEAL", label: "申诉（我觉得也对）" },
  { value: "DISPUTE", label: "不认同（排除）" },
];

/** 状态列的中文名与色调。 */
const STATUS_LABELS: Record<string, { label: string; tone: "ok" | "warn" | "muted" }> = {
  PENDING: { label: "待终审", tone: "warn" },
  ACCEPTED: { label: "已通过", tone: "ok" },
  REJECTED: { label: "已驳回", tone: "muted" },
  WITHDRAWN: { label: "已撤回", tone: "muted" },
};

/** AI 初裁结论中文名；UNAVAILABLE 与 null 都表示无有效 AI 意见。 */
const VERDICT_LABELS: Record<string, string> = {
  SUPPORT: "支持",
  OPPOSE: "反对",
  UNCERTAIN: "不确定",
  UNAVAILABLE: "初裁不可用",
};

/** 终审理由弹窗的预设选项：按结论分化，减少打字成本；ReasonDialog 自带「其他」自定义项，理由必填。 */
const DECIDE_PRESETS = {
  ACCEPTED: ["写法与规范答案语义等价"],
  REJECTED: ["与参考语义不符", "引入新的语言错误"],
} as const;

/**
 * 申诉页签（025）：LLM 初裁之后的人工终审关口。
 * 列表展示状态（含 AI 意见摘要）、题目、槽位、用户提议与理由；行操作「通过/驳回」
 * 必填终审理由并写审计（APPEAL_DECIDE）。ACCEPTED 的申诉经判分 overlay 参与后续判分。
 */
export function AppealsPage() {
  const [status, setStatus] = useState("all");
  const [kind, setKind] = useState("all");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const queryClient = useQueryClient();
  const toast = useToastMessage();
  /** 待终审目标：null 关闭弹窗；id 为 AdminAppeal.id（idSchema 归一后的字符串），reason 随弹窗填写。 */
  const [deciding, setDeciding] = useState<{
    id: string;
    target: "ACCEPTED" | "REJECTED";
  } | null>(null);

  const appealsQuery = useQuery({
    queryKey: queryKeys.admin.appeals({ page, size, status, kind }),
    queryFn: ({ signal }) =>
      getAdminAppeals(
        {
          page,
          size,
          ...(status === "all" ? {} : { status }),
          ...(kind === "all" ? {} : { kind }),
        },
        signal,
      ),
  });

  const decide = useMutation({
    mutationFn: (input: { id: string; target: "ACCEPTED" | "REJECTED"; reason: string }) =>
      decideAdminAppeal(input.id, { status: input.target, reason: input.reason }),
    onSuccess: (updated) => {
      setDeciding(null);
      void queryClient.invalidateQueries({ queryKey: ["admin", "appeals"] });
      void queryClient.invalidateQueries({ queryKey: ["admin", "overview"] });
      toast.show(
        updated.status === "ACCEPTED"
          ? "申诉已通过：该写法加入可接受答案"
          : "申诉已驳回：题目与判分不变",
      );
    },
  });

  return (
    <div>
      <div className="mb-2.5 flex flex-wrap items-center gap-3.5">
        <AdminSelect
          aria-label="状态筛选"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value);
            setPage(1);
          }}
        >
          {STATUS_FILTER_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </AdminSelect>
        <AdminSelect
          aria-label="类型筛选"
          value={kind}
          onChange={(event) => {
            setKind(event.target.value);
            setPage(1);
          }}
        >
          {KIND_FILTER_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </AdminSelect>
      </div>

      {appealsQuery.isPending ? (
        <LoadingBlock label="正在载入申诉…" />
      ) : appealsQuery.isError ? (
        <ErrorState
          error={appealsQuery.error}
          onRetry={() => void appealsQuery.refetch()}
          variant="admin"
        />
      ) : (
        <>
          <RowList>
            {appealsQuery.data.items.length === 0 ? (
              <EmptyRow>没有待处理的申诉。</EmptyRow>
            ) : (
              appealsQuery.data.items.map((appeal) => {
                const statusInfo = STATUS_LABELS[appeal.status] ?? STATUS_LABELS.PENDING;
                const verdictLabel = appeal.decisionVerdict
                  ? (VERDICT_LABELS[appeal.decisionVerdict] ?? appeal.decisionVerdict)
                  : null;
                // AI 意见行三态：初裁成功=结论+理由；初裁占位/缺失=「AI 初裁暂不可用」；终审后=终审理由。
                // 存量 PENDING（初裁前提交）没有 decision，同样落「暂不可用」占位——人工终审不依赖 AI 意见。
                const aiOpinion =
                  appeal.decidedBy === "LLM" && verdictLabel && verdictLabel !== "初裁不可用"
                    ? `AI 意见：${verdictLabel}${appeal.decisionReason ? ` · ${appeal.decisionReason}` : ""}`
                    : null;
                const noOpinion =
                  appeal.decidedBy !== "LLM" &&
                  appeal.decidedBy !== "ADMIN" &&
                  appeal.status === "PENDING";
                return (
                  <Row key={appeal.id}>
                    <RowText
                      main={appeal.sentenceZh ?? appeal.sentenceId}
                      meta={
                        <>
                          {`${appeal.username ?? "未知用户"} · ${
                            appeal.kind === "APPEAL" ? "申诉" : "不认同"
                          } · 槽位 ${appeal.annotationKey} · ${relativeTimeLabel(appeal.createdAt)}`}
                          <br />
                          {appeal.proposedAnswer ? `提议：${appeal.proposedAnswer} · ` : ""}
                          {appeal.reason ? `理由：${appeal.reason}` : ""}
                          {aiOpinion ? (
                            <span
                              className="block cursor-help font-mono text-[10px] text-ink-muted"
                              title={appeal.decisionReason ?? undefined}
                            >
                              {aiOpinion} · 等待人工终审
                            </span>
                          ) : noOpinion ? (
                            <span className="block font-mono text-[10px] text-ink-faint">
                              AI 初裁暂不可用 · 等待人工终审
                            </span>
                          ) : appeal.decisionReason && appeal.decidedBy === "ADMIN" ? (
                            <span className="block font-mono text-[10px] text-ink-faint">
                              终审理由：{appeal.decisionReason}
                            </span>
                          ) : null}
                        </>
                      }
                    />
                    <StatePill tone={statusInfo.tone}>{statusInfo.label}</StatePill>
                    <span>
                      {appeal.status === "PENDING" ? (
                        <>
                          <RowAct
                            onClick={() => setDeciding({ id: appeal.id, target: "ACCEPTED" })}
                          >
                            通过
                          </RowAct>
                          <span aria-hidden="true" className="mx-2 text-rule">
                            /
                          </span>
                          <RowAct
                            onClick={() => setDeciding({ id: appeal.id, target: "REJECTED" })}
                          >
                            驳回
                          </RowAct>
                        </>
                      ) : (
                        <span className="font-mono text-[10px] text-ink-faint">已终审</span>
                      )}
                    </span>
                  </Row>
                );
              })
            )}
          </RowList>
          <Pagination
            page={appealsQuery.data.page}
            size={appealsQuery.data.size}
            total={appealsQuery.data.total}
            totalPages={appealsQuery.data.totalPages}
            hasNext={appealsQuery.data.hasNext}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(1);
            }}
          />
        </>
      )}

      <p className="mt-5 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
        AI 意见仅供参考，终审才改状态；通过后该写法作为可接受答案参与判分（不改题目原文），
        驳回不回写题目。每次终审写审计日志。
      </p>

      <ReasonDialog
        open={deciding !== null}
        title={deciding?.target === "ACCEPTED" ? "通过申诉" : "驳回申诉"}
        description={
          deciding?.target === "ACCEPTED"
            ? "通过后该写法进入判分 overlay（不改题目原文）。请填写终审理由（必填，写审计）。"
            : "驳回不修改题目与判分。请填写终审理由（必填，写审计）。"
        }
        options={deciding ? [...DECIDE_PRESETS[deciding.target]] : ["其他"]}
        onCancel={() => setDeciding(null)}
        busy={decide.isPending}
        onConfirm={(reason) => {
          if (!deciding || reason.trim() === "") {
            return;
          }
          decide.mutate({ id: deciding.id, target: deciding.target, reason: reason.trim() });
        }}
      />
      {/* 终审失败的可见反馈：弹窗保持打开（理由还在），错误卡在弹窗下方避免静默失败。 */}
      {decide.isError ? (
        <div role="alert" className="mt-4">
          <ErrorState error={decide.error} onRetry={() => decide.reset()} compact variant="admin" />
        </div>
      ) : null}
      {toast.node}
    </div>
  );
}
