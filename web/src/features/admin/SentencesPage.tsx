import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { Pagination } from "@/components/pagination";
import {
  type AdminSentence,
  bulkMoveSentences,
  bulkUpdateSentenceStatus,
  getAdminBanks,
  getAdminSentences,
  updateSentenceDetail,
  updateSentenceStatus,
} from "@/lib/api/admin";
import { queryKeys } from "@/lib/query";
import { LEVELS, levelLabel } from "./constants";
import {
  AdminField,
  AdminSearch,
  AdminSelect,
  EmptyRow,
  Modal,
  ReasonDialog,
  Row,
  RowAct,
  RowList,
  RowText,
  StatePill,
  useToastMessage,
} from "./ui";

/** 「填原因」弹窗的一次请求。 */
interface ReasonRequest {
  title: string;
  description: string;
  options: string[];
  onConfirm: (reason: string) => void;
}

/**
 * 题目页签：跨题库搜索与筛选、批量停用/启用/移动/删除、行内编辑与回收站恢复。
 * 参考译文与语块只读——直接改译文会破坏 chunks 与 ref 的一致性。
 */
export function SentencesPage() {
  const { show: toast, node: toastNode } = useToastMessage();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState("");
  const [bankId, setBankId] = useState("all");
  const [level, setLevel] = useState("all");
  const [status, setStatus] = useState("all");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [editing, setEditing] = useState<AdminSentence | null>(null);
  const [moveTarget, setMoveTarget] = useState("");
  const [reasonRequest, setReasonRequest] = useState<ReasonRequest | null>(null);

  const banksQuery = useQuery({
    queryKey: queryKeys.admin.banks({ scope: "filter", size: 100 }),
    queryFn: ({ signal }) => getAdminBanks({ page: 1, size: 100 }, signal),
  });
  const sentencesQuery = useQuery({
    queryKey: queryKeys.admin.bankSentences("all", { page, size, keyword, bankId, level, status }),
    queryFn: ({ signal }) =>
      getAdminSentences(
        {
          page,
          size,
          ...(keyword.trim() === "" ? {} : { keyword: keyword.trim() }),
          ...(bankId === "all" ? {} : { bankId }),
          ...(level === "all" ? {} : { level }),
          ...(status === "all" ? {} : { status }),
        },
        signal,
      ),
  });

  /** 写操作成功后的统一失效；题目与题库版本联动。 */
  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["admin", "sentences"] });
    void queryClient.invalidateQueries({ queryKey: ["admin", "banks"] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.bankSentences("all", {}) });
    void queryClient.invalidateQueries({ queryKey: ["admin", "overview"] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.auditLogs({}) });
  }

  const bulkStatusMutation = useMutation({
    mutationFn: (input: { ids: string[]; status: string; reason: string }) =>
      bulkUpdateSentenceStatus(input),
    onSuccess: (result) => {
      invalidate();
      setSelected(new Set());
      toast(`已处理 ${result.updated} 句`);
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });
  const bulkMoveMutation = useMutation({
    mutationFn: (input: { ids: string[]; bankId: string; reason: string }) =>
      bulkMoveSentences(input),
    onSuccess: (result) => {
      invalidate();
      setSelected(new Set());
      toast(`已移动 ${result.moved} 句`);
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });
  const restoreMutation = useMutation({
    mutationFn: (input: { id: string; reason: string }) =>
      updateSentenceStatus(input.id, { status: "PUBLISHED", reason: input.reason }),
    onSuccess: () => {
      invalidate();
      toast("已恢复");
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });

  const banks = banksQuery.data?.items ?? [];
  const bankName = (id: string) => banks.find((bank) => bank.id === id)?.name ?? id;
  const rows = sentencesQuery.data?.items ?? [];
  const selectedIds = [...selected];
  const toggle = (id: string, checked: boolean) => {
    setSelected((previous) => {
      const next = new Set(previous);
      if (checked) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  };

  return (
    <div>
      <div className="mb-2.5 flex flex-wrap items-center gap-3.5">
        <AdminSearch
          aria-label="搜索中文或译文"
          placeholder="搜索中文或译文"
          value={keyword}
          onChange={(event) => {
            setKeyword(event.target.value);
            setPage(1);
          }}
        />
        <AdminSelect
          aria-label="题库筛选"
          value={bankId}
          onChange={(event) => {
            setBankId(event.target.value);
            setPage(1);
          }}
        >
          <option value="all">全部题库</option>
          {banks.map((bank) => (
            <option key={bank.id} value={bank.id}>
              {bank.name}
            </option>
          ))}
        </AdminSelect>
        <AdminSelect
          aria-label="难度筛选"
          value={level}
          onChange={(event) => {
            setLevel(event.target.value);
            setPage(1);
          }}
        >
          <option value="all">全部难度</option>
          {LEVELS.map((item) => (
            <option key={item.id} value={item.id}>
              {item.label}
            </option>
          ))}
        </AdminSelect>
        <AdminSelect
          aria-label="状态筛选"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value);
            setPage(1);
          }}
        >
          <option value="all">全部状态</option>
          <option value="PUBLISHED">正常</option>
          <option value="DISABLED">已停用</option>
          <option value="RECYCLED">回收站</option>
        </AdminSelect>
      </div>

      {selectedIds.length > 0 ? (
        <div className="flex flex-wrap items-center gap-4 border-b border-rule pt-2.5 pb-3.5 font-mono text-[11px] text-ink-muted">
          <span>已选 {selectedIds.length} 句</span>
          <button
            type="button"
            className="linkish"
            disabled={bulkStatusMutation.isPending}
            onClick={() =>
              setReasonRequest({
                title: "批量停用题目",
                description: `将把 ${selectedIds.length} 句停用；停用后不再出题，可再启用。`,
                options: ["内容有误", "重复", "其他"],
                onConfirm: (reason) =>
                  bulkStatusMutation.mutate({ ids: selectedIds, status: "DISABLED", reason }),
              })
            }
          >
            停用
          </button>
          <button
            type="button"
            className="linkish"
            disabled={bulkStatusMutation.isPending}
            onClick={() =>
              setReasonRequest({
                title: "批量启用题目",
                description: `将把 ${selectedIds.length} 句恢复为正常出题。`,
                options: ["内容已修正", "误操作恢复", "其他"],
                onConfirm: (reason) =>
                  bulkStatusMutation.mutate({ ids: selectedIds, status: "PUBLISHED", reason }),
              })
            }
          >
            启用
          </button>
          <AdminSelect
            aria-label="批量移动到"
            value={moveTarget}
            disabled={bulkMoveMutation.isPending}
            onChange={(event) => {
              const target = event.target.value;
              setMoveTarget("");
              if (!target) {
                return;
              }
              setReasonRequest({
                title: "批量移动题目",
                description: `将把 ${selectedIds.length} 句移动到「${bankName(target)}」。`,
                options: ["归类调整", "题库整理", "其他"],
                onConfirm: (reason) =>
                  bulkMoveMutation.mutate({ ids: selectedIds, bankId: target, reason }),
              });
            }}
          >
            <option value="">移动到…</option>
            {banks.map((bank) => (
              <option key={bank.id} value={bank.id}>
                {bank.name}
              </option>
            ))}
          </AdminSelect>
          <button
            type="button"
            className="linkish"
            disabled={bulkStatusMutation.isPending}
            onClick={() =>
              setReasonRequest({
                title: "批量删除题目",
                description: `将把 ${selectedIds.length} 句移入回收站，可恢复。`,
                options: ["内容有误", "重复", "其他"],
                onConfirm: (reason) =>
                  bulkStatusMutation.mutate({ ids: selectedIds, status: "RECYCLED", reason }),
              })
            }
          >
            删除
          </button>
        </div>
      ) : null}

      {sentencesQuery.isPending ? (
        <LoadingBlock label="正在载入题目…" />
      ) : sentencesQuery.isError ? (
        <ErrorState error={sentencesQuery.error} onRetry={() => void sentencesQuery.refetch()} />
      ) : (
        <>
          <RowList>
            {rows.length === 0 ? (
              <EmptyRow>没有匹配的题目。</EmptyRow>
            ) : (
              rows.map((sentence) => {
                const recycled = sentence.status === "RECYCLED";
                return (
                  <Row key={sentence.id} cols="grid-cols-[22px_minmax(0,1fr)_auto_auto] gap-3.5">
                    <input
                      type="checkbox"
                      aria-label={`选择 ${sentence.zh}`}
                      className="mt-0.5 h-[15px] w-[15px] accent-vermilion"
                      checked={selected.has(sentence.id)}
                      onChange={(event) => toggle(sentence.id, event.target.checked)}
                    />
                    <RowText
                      main={sentence.zh}
                      meta={`${bankName(sentence.bankId)} · ${levelLabel(sentence.level)} · ${(sentence.tags ?? []).join(" / ")}`}
                    />
                    <StatePill
                      tone={
                        sentence.status === "PUBLISHED"
                          ? "muted"
                          : sentence.status === "DISABLED" || recycled
                            ? "warn"
                            : "muted"
                      }
                    >
                      {recycled ? "已删除" : sentence.status === "DISABLED" ? "已停用" : "正常"}
                    </StatePill>
                    {recycled ? (
                      <RowAct
                        disabled={restoreMutation.isPending}
                        onClick={() =>
                          setReasonRequest({
                            title: "恢复题目",
                            description: `恢复「${(sentence.zh ?? "").slice(0, 18)}…」并重新参与出题。`,
                            options: ["误删恢复", "内容已修正", "其他"],
                            onConfirm: (reason) =>
                              restoreMutation.mutate({ id: sentence.id, reason }),
                          })
                        }
                      >
                        恢复
                      </RowAct>
                    ) : (
                      <RowAct onClick={() => setEditing(sentence)}>编辑</RowAct>
                    )}
                  </Row>
                );
              })
            )}
          </RowList>
          <Pagination
            page={sentencesQuery.data.page}
            size={sentencesQuery.data.size}
            total={sentencesQuery.data.total}
            totalPages={sentencesQuery.data.totalPages}
            hasNext={sentencesQuery.data.hasNext}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(1);
            }}
          />
        </>
      )}

      <p className="mt-4 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
        共 {sentencesQuery.data?.total ?? 0} 句 · 已选 {selectedIds.length} 句
      </p>

      {editing ? (
        <SentenceEditModal
          key={editing.id}
          sentence={editing}
          banks={banks.map((bank) => ({ id: bank.id, name: bank.name }))}
          onClose={() => setEditing(null)}
          onSettled={invalidate}
          requestReason={setReasonRequest}
          toast={toast}
        />
      ) : null}

      <ReasonDialog
        open={reasonRequest !== null}
        title={reasonRequest?.title ?? ""}
        description={reasonRequest?.description ?? ""}
        options={reasonRequest?.options ?? ["其他"]}
        onCancel={() => setReasonRequest(null)}
        onConfirm={(reason) => {
          const request = reasonRequest;
          setReasonRequest(null);
          request?.onConfirm(reason);
        }}
      />

      {toastNode}
    </div>
  );
}

/**
 * 题目编辑弹窗（原型 430px .modal）：中文、难度、标签、解析、归属题库与状态可改；
 * 参考译文与语块只读（原型注明：直接改译文会破坏 chunks 与 ref 的一致性）。
 */
function SentenceEditModal({
  sentence,
  banks,
  onClose,
  onSettled,
  requestReason,
  toast,
}: {
  sentence: AdminSentence;
  banks: Array<{ id: string; name: string }>;
  onClose: () => void;
  onSettled: () => void;
  requestReason: (request: ReasonRequest) => void;
  toast: (message: string) => void;
}) {
  const [zh, setZh] = useState(sentence.zh ?? "");
  const [level, setLevel] = useState(sentence.level ?? "CUSTOM");
  const [tags, setTags] = useState((sentence.tags ?? []).join("，"));
  const [note, setNote] = useState(sentence.note ?? "");
  const [bankId, setBankId] = useState(sentence.bankId);
  const [status, setStatus] = useState<string>(sentence.status);

  const saveMutation = useMutation({
    mutationFn: () =>
      updateSentenceDetail(sentence.id, {
        zh: zh.trim(),
        level,
        tags: tags
          .split(/[，,]/)
          .map((tag) => tag.trim())
          .filter(Boolean),
        note: note.trim(),
        bankId,
        status,
        reason: "编辑题目",
      }),
    onSuccess: () => {
      onSettled();
      onClose();
      toast("已保存");
    },
    onError: (error) => toast(error instanceof Error ? error.message : "保存失败"),
  });
  const deleteMutation = useMutation({
    mutationFn: (reason: string) =>
      updateSentenceStatus(sentence.id, { status: "RECYCLED", reason }),
    onSuccess: () => {
      onSettled();
      onClose();
      toast("已移入回收站");
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });

  return (
    <Modal
      open
      onClose={onClose}
      kicker={`编辑题目 · ${sentence.id.slice(0, 10)}`}
      title={(sentence.zh ?? "").slice(0, 18)}
    >
      <div className="mt-4">
        <AdminField label="中文">
          <input
            value={zh}
            onChange={(event) => setZh(event.target.value)}
            aria-label="中文原句"
            className="w-full border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition focus:border-vermilion"
          />
        </AdminField>
        <AdminField label="难度">
          <AdminSelect
            value={level}
            onChange={(event) => setLevel(event.target.value)}
            aria-label="难度"
          >
            {LEVELS.map((item) => (
              <option key={item.id} value={item.id}>
                {item.label}
              </option>
            ))}
          </AdminSelect>
        </AdminField>
        <AdminField label="标签（逗号分隔）">
          <input
            value={tags}
            onChange={(event) => setTags(event.target.value)}
            aria-label="标签"
            className="w-full border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition focus:border-vermilion"
          />
        </AdminField>
        <AdminField label="所属题库">
          <AdminSelect
            value={bankId}
            onChange={(event) => setBankId(event.target.value)}
            aria-label="所属题库"
          >
            {banks.map((bank) => (
              <option key={bank.id} value={bank.id}>
                {bank.name}
              </option>
            ))}
          </AdminSelect>
        </AdminField>
        <AdminField label="状态">
          <AdminSelect
            value={status}
            onChange={(event) => setStatus(event.target.value)}
            aria-label="状态"
          >
            <option value="PUBLISHED">正常</option>
            <option value="DISABLED">停用</option>
          </AdminSelect>
        </AdminField>
        <AdminField label="解析（导入时填写）">
          <textarea
            rows={3}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            aria-label="解析"
            className="min-h-24 w-full resize-y rounded-[2px] border border-rule bg-transparent px-2.5 py-2.5 text-[13px] leading-[1.7] text-ink outline-none transition focus:border-ink-faint"
          />
        </AdminField>
        <p className="font-mono text-[10px] leading-[1.6] tracking-[0.04em] text-ink-faint">
          参考译文与语块只读：直接改译文会破坏 chunks 与 ref 的一致性，结构编辑放在「高级编辑」。
        </p>
        <div className="mt-[22px] flex items-center justify-between gap-3">
          <button
            type="button"
            className="linkish"
            disabled={deleteMutation.isPending}
            onClick={() =>
              requestReason({
                title: "删除题目",
                description: "删除后进入回收站，可恢复；已有的复习记录会保留但不再出题。",
                options: ["内容有误", "重复", "其他"],
                onConfirm: (reason) => deleteMutation.mutate(reason),
              })
            }
          >
            删除
          </button>
          <div className="flex gap-3">
            <button type="button" className="btn ghost" onClick={onClose}>
              取消
            </button>
            <button
              type="button"
              className="btn primary"
              disabled={saveMutation.isPending || zh.trim().length < 4}
              onClick={() => saveMutation.mutate()}
            >
              {saveMutation.isPending ? "保存中…" : "保存"}
            </button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
