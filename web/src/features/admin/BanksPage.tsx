import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { Pagination } from "@/components/pagination";
import { ImportSection } from "@/features/uploads/ImportSection";
import {
  createAdminBank,
  deleteAdminBank,
  getAdminBankSentences,
  getAdminBanks,
  updateAdminBankStatus,
} from "@/lib/api/admin";
import { queryKeys } from "@/lib/query";
import type { BankView } from "@/lib/schemas/content";
import { LEVELS, levelLabel, RESERVED_LEVEL_ID } from "./constants";
import {
  AdminField,
  AdminSection,
  Drawer,
  EmptyRow,
  Modal,
  ReasonDialog,
  Row,
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

/** 难度 chips 的原型 .var 语言：10px mono 小片，可删的带 ×；悬停转朱红。 */
function levelChipClassName(): string {
  return "rounded-sm border border-rule bg-transparent px-[7px] py-[3px] font-mono text-[10px] text-ink-muted transition-colors hover:border-vermilion hover:text-vermilion";
}

/**
 * 题库页签：原型三段式——现有题库 → 难度配置 → 导入。
 * 导入由 UploadPage 折叠为页内区块（ImportSection），上传/轮询/行错误/resume 数据流不变。
 * 官方题库不可停用、不可删除；难度配置为前端演示态，刷新后恢复默认。
 */
export function BanksPage() {
  const { show: toast, node: toastNode } = useToastMessage();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [creating, setCreating] = useState(false);
  const [drawerBankId, setDrawerBankId] = useState<string | null>(null);
  const [levels, setLevels] = useState(LEVELS);
  const [newLevel, setNewLevel] = useState("");
  const [reasonRequest, setReasonRequest] = useState<ReasonRequest | null>(null);

  const banksQuery = useQuery({
    queryKey: queryKeys.admin.banks({ page, size }),
    queryFn: ({ signal }) => getAdminBanks({ page, size }, signal),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["admin", "banks"] });
    void queryClient.invalidateQueries({ queryKey: ["admin", "sentences"] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.bankSentences("all", {}) });
    void queryClient.invalidateQueries({ queryKey: ["admin", "overview"] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.auditLogs({}) });
  }

  const createMutation = useMutation({
    mutationFn: createAdminBank,
    onSuccess: (bank) => {
      invalidate();
      setCreating(false);
      toast(`已创建「${bank.name}」`);
    },
    onError: (error) => toast(error instanceof Error ? error.message : "创建失败"),
  });
  const toggleMutation = useMutation({
    mutationFn: (input: { bank: BankView; status: string; reason: string }) =>
      updateAdminBankStatus(input.bank.id, { status: input.status, reason: input.reason }),
    onSuccess: (bank) => {
      invalidate();
      setDrawerBankId(null);
      toast(`已${bank.status === "DISABLED" ? "停用" : "恢复"}「${bank.name}」`);
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });
  const deleteMutation = useMutation({
    mutationFn: (input: { bank: BankView; reason: string }) =>
      deleteAdminBank(input.bank.id, input.reason),
    onSuccess: () => {
      invalidate();
      setDrawerBankId(null);
      toast("已删除（可从服务端回收）");
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });

  const banks = banksQuery.data?.items ?? [];
  const drawerBank = banks.find((bank) => bank.id === drawerBankId) ?? null;

  return (
    <div>
      <AdminSection
        title="现有题库"
        className="mt-0"
        action={
          <button type="button" className="linkish" onClick={() => setCreating(true)}>
            新建
          </button>
        }
      >
        {banksQuery.isPending ? (
          <LoadingBlock label="正在载入题库…" />
        ) : banksQuery.isError ? (
          <ErrorState error={banksQuery.error} onRetry={() => void banksQuery.refetch()} />
        ) : (
          <>
            <RowList>
              {banks.length === 0 ? (
                <EmptyRow>还没有题库，先新建一个。</EmptyRow>
              ) : (
                banks.map((bank) => (
                  <Row key={bank.id}>
                    <RowText
                      main={bank.name}
                      meta={`${bank.sentenceCount} 句 · ${bank.visibility === "PUBLIC" ? "公开" : "私有"}`}
                    />
                    <StatePill
                      tone={
                        bank.status === "DISABLED"
                          ? "warn"
                          : bank.visibility === "PUBLIC"
                            ? "ok"
                            : "muted"
                      }
                    >
                      {bank.status === "DISABLED"
                        ? "已停用"
                        : bank.visibility === "PUBLIC"
                          ? "公开"
                          : "私有"}
                    </StatePill>
                    {/* 公开题库只读（官方题库不可改），行动作按可见性区分「查看 / 管理」。 */}
                    <button
                      type="button"
                      className="text-xs text-ink-muted transition-colors hover:text-vermilion"
                      onClick={() => setDrawerBankId(bank.id)}
                    >
                      {bank.visibility === "PUBLIC" ? "查看" : "管理"}
                    </button>
                  </Row>
                ))
              )}
            </RowList>
            <Pagination
              page={banksQuery.data.page}
              size={banksQuery.data.size}
              total={banksQuery.data.total}
              totalPages={banksQuery.data.totalPages}
              hasNext={banksQuery.data.hasNext}
              onPageChange={setPage}
              onSizeChange={(next) => {
                setSize(next);
                setPage(1);
              }}
            />
          </>
        )}
      </AdminSection>

      <AdminSection title="难度配置">
        <div className="flex flex-wrap gap-2">
          {levels.map((item) => (
            <button
              key={item.id}
              type="button"
              className={levelChipClassName()}
              title={
                item.id === RESERVED_LEVEL_ID
                  ? "「自建」是保留难度"
                  : "还有句子在用的时候不能删（演示：点击直接移除）"
              }
              onClick={() => {
                if (item.id === RESERVED_LEVEL_ID) {
                  toast("「自建」是保留难度");
                  return;
                }
                setLevels((previous) => previous.filter((level) => level.id !== item.id));
                toast("已删除难度（演示，刷新后恢复）");
              }}
            >
              {item.label}
              {item.id === RESERVED_LEVEL_ID ? "" : " ×"}
            </button>
          ))}
        </div>
        <div className="mt-3 flex items-center gap-3.5">
          <input
            aria-label="新增难度"
            placeholder="新增难度，如 GRE"
            value={newLevel}
            onChange={(event) => setNewLevel(event.target.value)}
            className="min-w-45 flex-1 border-b border-rule bg-transparent py-2 text-[13px] text-ink outline-none transition placeholder:text-ink-faint focus:border-vermilion"
          />
          <button
            type="button"
            className="btn ghost px-[22px] py-2.5"
            onClick={() => {
              const value = newLevel.trim();
              if (!value) {
                return;
              }
              const id = value.toUpperCase().replace(/\s+/g, "_");
              if (levels.some((level) => level.id === id)) {
                toast("这个难度已存在");
                return;
              }
              setLevels((previous) => [...previous, { id, label: value }]);
              setNewLevel("");
              toast(`已添加「${value}」（演示，刷新后恢复）`);
            }}
          >
            添加
          </button>
        </div>
        <p className="mt-2.5 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
          难度会出现在出题表单与导入校验里；还有句子在用的时候不能删。（演示配置）
        </p>
      </AdminSection>

      <AdminSection title="导入">
        <ImportSection />
      </AdminSection>

      <Modal open={creating} onClose={() => setCreating(false)} kicker="新建题库" title="题库信息">
        <BankForm
          submitLabel="创建"
          pending={createMutation.isPending}
          onSubmit={(input) => createMutation.mutate(input)}
          onCancel={() => setCreating(false)}
        />
      </Modal>

      {drawerBank ? (
        <BankDrawer
          key={drawerBank.id}
          bank={drawerBank}
          onClose={() => setDrawerBankId(null)}
          onToggle={(status) => {
            if (drawerBank.id === "1") {
              toast("官方题库不能停用");
              return;
            }
            setReasonRequest({
              title: status === "DISABLED" ? "停用题库" : "恢复题库",
              description:
                status === "DISABLED"
                  ? "停用后不再出现在练习与导入目标里，句子保留。"
                  : "恢复后该题库重新参与练习与出题。",
              options: ["不再使用", "误操作恢复", "其他"],
              onConfirm: (reason) => toggleMutation.mutate({ bank: drawerBank, status, reason }),
            });
          }}
          onDelete={() => {
            if (drawerBank.id === "1") {
              toast("官方题库不能删除");
              return;
            }
            setReasonRequest({
              title: "删除题库",
              description: "删除后不再出现在练习和导入目标里，句子保留但不参与出题。",
              options: ["不再使用", "重复创建", "其他"],
              onConfirm: (reason) => deleteMutation.mutate({ bank: drawerBank, reason }),
            });
          }}
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

/** 新建/编辑题库的表单；编辑弹窗本轮未开放（原型保留），只服务新建。 */
function BankForm({
  submitLabel,
  pending,
  onSubmit,
  onCancel,
}: {
  submitLabel: string;
  pending: boolean;
  onSubmit: (input: {
    name: string;
    description: string | null;
    visibility: "PUBLIC" | "PRIVATE";
  }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [visibility, setVisibility] = useState<"PUBLIC" | "PRIVATE">("PRIVATE");
  return (
    <div className="mt-4">
      <AdminField label="名称">
        <input
          // biome-ignore lint/a11y/noAutofocus: 弹窗打开即聚焦名称输入，与原型行为一致，焦点在弹窗内不会跳走。
          autoFocus
          aria-label="题库名称"
          placeholder="例：商务邮件常用句"
          value={name}
          onChange={(event) => setName(event.target.value)}
          className="w-full border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition placeholder:text-ink-faint focus:border-vermilion"
        />
      </AdminField>
      <AdminField label="可见性">
        <select
          value={visibility}
          onChange={(event) => setVisibility(event.target.value as "PUBLIC" | "PRIVATE")}
          aria-label="可见性"
          className="rounded-sm border border-rule bg-transparent px-2.5 py-[7px] text-xs text-ink-muted outline-none transition focus:border-ink-faint"
        >
          <option value="PRIVATE">私有</option>
          <option value="PUBLIC">公开</option>
        </select>
      </AdminField>
      <div className="mt-6 flex justify-end gap-3">
        <button type="button" className="btn ghost" onClick={onCancel}>
          取消
        </button>
        <button
          type="button"
          className="btn primary"
          disabled={pending || name.trim() === ""}
          onClick={() => onSubmit({ name: name.trim(), description: null, visibility })}
        >
          {submitLabel}
        </button>
      </div>
    </div>
  );
}

/** 题库抽屉：练习入口、句子列表与边界操作；停用/删除的原因弹窗由页面层提供。 */
function BankDrawer({
  bank,
  onClose,
  onToggle,
  onDelete,
}: {
  bank: BankView;
  onClose: () => void;
  onToggle: (status: "DISABLED" | "PUBLISHED") => void;
  onDelete: () => void;
}) {
  const sentencesQuery = useQuery({
    queryKey: queryKeys.admin.bankSentences(bank.id, { page: 1, size: 20 }),
    queryFn: ({ signal }) => getAdminBankSentences(bank.id, { page: 1, size: 20 }, signal),
  });
  const records = sentencesQuery.data?.items ?? [];
  const isOfficial = bank.id === "1";
  return (
    <Drawer
      open
      onClose={onClose}
      kicker={`题库 · ${bank.id}`}
      title={bank.name}
      meta={`${bank.sentenceCount} 句 · ${bank.visibility === "PUBLIC" ? "公开" : "私有"}${bank.status === "DISABLED" ? " · 已停用" : ""}`}
    >
      <div className="mt-[22px] flex flex-wrap gap-4">
        <Link
          to="/practice"
          search={(previous) => ({ ...previous, bankId: bank.id })}
          className="linkish"
        >
          在这个题库练习
        </Link>
        {isOfficial ? null : (
          <>
            <button
              type="button"
              className="linkish text-ink-muted border-rule hover:text-vermilion"
              onClick={() => onToggle(bank.status === "DISABLED" ? "PUBLISHED" : "DISABLED")}
            >
              {bank.status === "DISABLED" ? "恢复" : "停用"}
            </button>
            <button type="button" className="linkish" onClick={onDelete}>
              删除
            </button>
          </>
        )}
      </div>
      {isOfficial ? (
        <p className="mt-3 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
          官方题库不可停用、不可删除。
        </p>
      ) : null}

      <p className="section-title mt-[30px]">题目</p>
      {sentencesQuery.isPending ? (
        <LoadingBlock label="正在载入题目…" rows={2} />
      ) : records.length === 0 ? (
        <p className="py-4 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
          这个题库还没有句子。
        </p>
      ) : (
        records.map((sentence) => (
          <div
            key={sentence.id}
            className="flex items-baseline justify-between gap-3.5 border-b border-rule py-[11px]"
          >
            <div className="min-w-0">
              <p className="truncate font-serif text-[13px] text-ink">{sentence.zh}</p>
              <p className="mt-[5px] font-mono text-[9.5px] tracking-[0.06em] text-ink-faint">
                {levelLabel(sentence.level)} · {(sentence.tags ?? []).join(" / ")} · 挖{" "}
                {(sentence.blank ?? []).length} 空
                {sentence.status === "DISABLED" ? " · 已停用" : ""}
              </p>
            </div>
            <Link
              to="/practice"
              search={(previous) => ({ ...previous, sentenceId: sentence.id })}
              className="linkish flex-none text-[11px]"
            >
              练习
            </Link>
          </div>
        ))
      )}
    </Drawer>
  );
}
