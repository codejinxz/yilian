import { useMutation, useQuery } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Select } from "@/components/ui/select";
import { commitImport, getImportBatch, previewImport, rollbackImport } from "@/lib/api/admin";
import { getBanks } from "@/lib/api/content";

const MAX_BYTES = 5 * 1024 * 1024;

/**
 * 题库页签第三段「导入」：选择文件 → 同步预览（行级错误就地展示）→ 确认提交入库 →
 * 可回滚（RECYCLED 软删 + 增量流下发 tombstone）。契约 /admin/uploads（切片设计 §4.1）。
 * 权限由管理端路由门（AdminGate）统一把关，这里不再单独查 me。
 */
export function ImportSection() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [batchId, setBatchId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rollbackReason, setRollbackReason] = useState("");
  const [rollbackError, setRollbackError] = useState<string | null>(null);
  // 拖拽悬停时给虚线框加深描边；只做视觉反馈，落点仍走同一套 choose 校验。
  const [dragging, setDragging] = useState(false);
  // 导入目标题库（原型 .import-target）：常显，预览时随 FormData 提交。
  const [bankId, setBankId] = useState("");

  const banksQuery = useQuery({
    queryKey: ["banks", { scope: "import", page: 1, size: 100 }],
    queryFn: ({ signal }) => getBanks({ page: 1, size: 100 }, signal),
  });
  const banks = banksQuery.data?.items ?? [];
  // 未手动选择时默认第一个可用题库，保证「开始预览」始终有明确归属。
  const effectiveBankId = bankId || banks[0]?.id || "";

  const preview = useMutation({
    mutationFn: (selected: File) =>
      previewImport({ file: selected, bankId: effectiveBankId, format: formatOf(selected.name) }),
    onSuccess: (result) => {
      setBatchId(result.batchId);
      setError(null);
      setRollbackError(null);
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : "预览失败"),
  });

  const detail = useQuery({
    queryKey: ["admin", "uploads", batchId],
    queryFn: ({ signal }) => getImportBatch(batchId ?? "", { page: 1, size: 50 }, signal),
    enabled: batchId !== null,
    retry: 1,
  });

  const commit = useMutation({
    mutationFn: (id: string) => commitImport(id),
    onSuccess: () => void detail.refetch(),
    onError: (cause) => setError(cause instanceof Error ? cause.message : "提交失败"),
  });

  const rollback = useMutation({
    mutationFn: (id: string) => rollbackImport(id, rollbackReason),
    onSuccess: () => {
      setRollbackReason("");
      setRollbackError(null);
      void detail.refetch();
    },
    onError: (cause) => setRollbackError(cause instanceof Error ? cause.message : "回滚失败"),
  });

  /** 文件选择的统一入口：清掉上一轮状态，做体量与格式的前端校验。 */
  const choose = (selected: File | null) => {
    setError(null);
    setBatchId(null);
    setRollbackReason("");
    setRollbackError(null);
    setFile(null);
    if (!selected) {
      return;
    }
    if (selected.size > MAX_BYTES) {
      setError("文件不能超过 5MB");
      return;
    }
    if (!/\.(csv|json)$/i.test(selected.name)) {
      setError("只支持 CSV 或 JSON");
      return;
    }
    setFile(selected);
  };

  const batch = detail.data?.batch;
  const committed = batch?.status === "COMMITTED";
  const rolledBack = batch?.status === "ROLLED_BACK";
  const invalidRows = detail.data?.rows.items.filter((row) => row.status === "INVALID") ?? [];

  return (
    <div>
      {/* 原型 .import-target：导入目标题库，常显；预览时随 FormData 提交 bankId。 */}
      <div className="mb-4 flex items-center gap-2.5 text-xs text-ink-muted">
        <span>导入到</span>
        <Select
          aria-label="导入目标题库"
          value={effectiveBankId}
          onChange={(event) => setBankId(event.target.value)}
          className="w-auto rounded-[2px] border-rule bg-transparent px-2.5 py-[7px] text-xs text-ink-muted"
          disabled={preview.isPending || committed}
        >
          {banks.length === 0 ? (
            <option value="">（暂无可用题库）</option>
          ) : (
            banks.map((bank) => (
              <option key={bank.id} value={bank.id}>
                {bank.name}
              </option>
            ))
          )}
        </Select>
      </div>

      {/* 原型 .import-box：虚线边拖拽框 + 说明行 + 右侧选择文件按钮。 */}
      <label
        className={
          dragging
            ? "flex flex-wrap items-center justify-between gap-5 border border-dashed border-vermilion/50 px-6 py-6"
            : "flex flex-wrap items-center justify-between gap-5 border border-dashed border-rule px-6 py-6"
        }
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          if (!preview.isPending) {
            choose(event.dataTransfer.files?.[0] ?? null);
          }
        }}
      >
        <div>
          <p className="text-[13px] text-ink">拖入 JSON / CSV，或点右侧选择文件</p>
          <p className="mt-2 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
            单文件上限 5MB · 先预览校验再确认入库
          </p>
        </div>
        <span
          aria-hidden="true"
          className="rounded-sm border border-rule px-5 py-2.5 text-[13px] tracking-[0.06em] text-ink-muted transition-colors hover:border-ink-faint hover:text-ink"
        >
          选择文件
        </span>
        <input
          ref={inputRef}
          type="file"
          accept=".csv,.json,text/csv,application/json"
          disabled={preview.isPending || committed}
          className="sr-only"
          aria-label="选择导入文件"
          onChange={(event) => choose(event.target.files?.[0] ?? null)}
        />
      </label>

      {file && batchId === null ? (
        <div className="mt-6 flex flex-wrap items-center justify-between gap-5">
          <p className="font-mono text-[11px] tracking-[0.06em] text-ink-faint">{file.name}</p>
          <div className="flex items-center gap-4">
            <button
              type="button"
              className="linkish"
              onClick={() => {
                setFile(null);
                setError(null);
                if (inputRef.current) {
                  inputRef.current.value = "";
                }
              }}
            >
              重新选择
            </button>
            <button
              type="button"
              className="btn primary px-5 py-2.5"
              disabled={preview.isPending}
              onClick={() => preview.mutate(file)}
            >
              {preview.isPending ? "预览中…" : "开始预览"}
            </button>
          </div>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="mt-4 text-sm text-vermilion">
          {error}
        </p>
      ) : null}

      {batch ? (
        <div aria-live="polite">
          <p className="mt-5 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
            批次 {batch.batchId.slice(0, 8)} · 共 {batch.totalRows} 行，通过 {batch.validRows}，
            失败 {batch.invalidRows}
            {batch.importedRows > 0 ? `，已入库 ${batch.importedRows}` : ""}
          </p>
          <p className="mt-2 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
            状态：{batch.status}
          </p>
          {batch.errorSummary ? (
            <p className="mt-2 text-sm text-vermilion">{batch.errorSummary.message}</p>
          ) : null}

          {/* 行级失败清单：原型 validation-table 的行式语言，只保留真实存在的「行 / 原因」两列。 */}
          {invalidRows.length ? (
            <table className="mt-5 w-full border-collapse">
              <thead>
                <tr>
                  {["行", "校验结果"].map((head) => (
                    <th
                      key={head}
                      className="border-b border-rule pb-2.5 pr-3 text-left font-mono text-[9px] font-normal tracking-[0.16em] text-ink-faint"
                    >
                      {head}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {invalidRows.slice(0, 20).map((item) => (
                  <tr key={item.rowNo} className="text-vermilion">
                    <td className="w-9 border-b border-rule py-[13px] pr-3 font-mono text-[10px] text-ink-faint">
                      {item.rowNo}
                    </td>
                    <td className="border-b border-rule py-[13px] pr-3 text-[13px] leading-[1.6]">
                      {item.errors.map((cause) => cause.message).join("；")}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}

          {/* 提交：预览后有合法行即可入库；已提交或已回滚不再提供按钮。 */}
          {batch.status === "PREVIEWED" && batch.validRows > 0 ? (
            <div className="mt-6 flex justify-end">
              <button
                type="button"
                className="btn primary px-5 py-2.5"
                disabled={commit.isPending}
                onClick={() => commit.mutate(batch.batchId)}
              >
                {commit.isPending ? "提交中…" : `提交 ${batch.validRows} 行入库`}
              </button>
            </div>
          ) : null}

          {/* 回滚：仅已提交批次可回滚；原因必填（审计要求），重复回滚幂等。 */}
          {committed ? (
            <div className="mt-6 border-t border-rule pt-5">
              <label htmlFor="rollback-reason" className={fieldLabelClass()}>
                回滚原因（必填，写审计）
              </label>
              <div className="mt-2 flex items-center gap-3">
                <input
                  id="rollback-reason"
                  value={rollbackReason}
                  onChange={(event) => setRollbackReason(event.target.value)}
                  placeholder="例如：误导入旧题库"
                  className="flex-1 rounded-[2px] border border-rule bg-transparent px-3 py-2 text-[13px] text-ink"
                />
                <button
                  type="button"
                  className="btn primary px-5 py-2.5"
                  disabled={rollback.isPending || !rollbackReason.trim()}
                  onClick={() => rollback.mutate(batch.batchId)}
                >
                  {rollback.isPending ? "回滚中…" : "回滚批次"}
                </button>
              </div>
              {rollbackError ? (
                <p role="alert" className="mt-2 text-sm text-vermilion">
                  {rollbackError}
                </p>
              ) : null}
            </div>
          ) : null}

          {rolledBack ? (
            <p className="mt-4 text-sm text-ink-muted">
              批次已回滚：题目进入回收站（软删），增量流将下发 tombstone。
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

/**
 * 由文件扩展名推断声明格式。
 *
 * @param filename 文件名
 * @returns {@code JSON} / {@code CSV}
 */
function formatOf(filename: string): "JSON" | "CSV" {
  return /\.csv$/i.test(filename) ? "CSV" : "JSON";
}

/** 字段标签样式（原型 field-label 语言）。 */
function fieldLabelClass(): string {
  return "font-mono text-[9px] font-normal tracking-[0.16em] text-ink-faint";
}
