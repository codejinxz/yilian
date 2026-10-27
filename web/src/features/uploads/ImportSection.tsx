import { useMutation, useQuery } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Select } from "@/components/ui/select";
import { getBanks } from "@/lib/api/content";
import { getImport, getImportErrors, resumeImport, uploadImport } from "@/lib/api/upload";

const MAX_BYTES = 5 * 1024 * 1024;

/**
 * 题库页签第三段「导入」：上传 CSV/JSON，后台校验，前端轮询批次状态，失败可继续。
 * 由 UploadPage 折叠而来（原型里导入本就是题库页签内的一段）；上传/轮询/行错误/resume
 * 的数据流保持不变，只换了位置与视觉（原型 import-box / validation-table / import-foot 语言）。
 * 权限由管理端路由门（AdminGate）统一把关，这里不再单独查 me。
 */
export function ImportSection() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [batchId, setBatchId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 拖拽悬停时给虚线框加深描边；只做视觉反馈，落点仍走同一套 choose 校验。
  const [dragging, setDragging] = useState(false);
  // 导入目标题库（原型 .import-target）：常显，上传时随 FormData 提交。
  const [bankId, setBankId] = useState("");

  const banksQuery = useQuery({
    queryKey: ["banks", { scope: "import", page: 1, size: 100 }],
    queryFn: ({ signal }) => getBanks({ page: 1, size: 100 }, signal),
  });
  const banks = banksQuery.data?.items ?? [];
  // 未手动选择时默认第一个可用题库，保证「开始上传」始终有明确归属。
  const effectiveBankId = bankId || banks[0]?.id || "";

  const upload = useMutation({
    mutationFn: (selected: File) => uploadImport(selected, effectiveBankId),
    onSuccess: (accepted) => {
      setBatchId(accepted.batchId);
      setError(null);
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : "上传失败"),
  });

  const batch = useQuery({
    queryKey: ["admin", "uploads", batchId],
    queryFn: ({ signal }) => getImport(batchId ?? "", signal),
    enabled: batchId !== null,
    refetchInterval: (query) => {
      const current = query.state.data;
      if (current?.status === "UPLOADED" || current?.status === "PROCESSING") {
        return 1000;
      }
      return query.state.error ? 2000 : false;
    },
    retry: 1,
  });

  const errors = useQuery({
    queryKey: ["admin", "uploads", batchId, "errors"],
    queryFn: ({ signal }) => getImportErrors(batchId ?? "", signal),
    enabled: batchId !== null && (batch.data?.failedRows ?? 0) > 0,
    retry: false,
  });

  /** 文件选择的统一入口：清掉上一轮状态，做体量与格式的前端校验。 */
  const choose = (selected: File | null) => {
    setError(null);
    setBatchId(null);
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

  const processing = batch.data?.status === "PROCESSING" || batch.data?.status === "UPLOADED";

  return (
    <div>
      {/* 原型 .import-target：导入目标题库，常显；上传时随 FormData 提交 bankId。 */}
      <div className="mb-4 flex items-center gap-2.5 text-xs text-ink-muted">
        <span>导入到</span>
        <Select
          aria-label="导入目标题库"
          value={effectiveBankId}
          onChange={(event) => setBankId(event.target.value)}
          className="w-auto rounded-[2px] border-rule bg-transparent px-2.5 py-[7px] text-xs text-ink-muted"
          disabled={upload.isPending}
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
          if (!upload.isPending) {
            choose(event.dataTransfer.files?.[0] ?? null);
          }
        }}
      >
        <div>
          <p className="text-[13px] text-ink">拖入 JSON / CSV，或点右侧选择文件</p>
          <p className="mt-2 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
            单文件上限 5MB · 先校验再入库
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
          disabled={upload.isPending}
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
              disabled={upload.isPending}
              onClick={() => upload.mutate(file)}
            >
              {upload.isPending ? "上传中…" : "开始上传"}
            </button>
          </div>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="mt-4 text-sm text-vermilion">
          {error}
        </p>
      ) : null}

      {batchId ? (
        <div aria-live="polite">
          <p className="mt-5 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
            批次 {batchId.slice(0, 8)}
            {batch.data?.bankId
              ? ` · ${banks.find((bank) => bank.id === batch.data?.bankId)?.name ?? batch.data.bankId}`
              : ""}
            {batch.data
              ? processing
                ? ` · 已处理 ${batch.data.processedRows} 行…`
                : ` · 已处理 ${batch.data.processedRows} 行，成功 ${batch.data.successRows}，失败 ${batch.data.failedRows}`
              : ""}
          </p>
          <progress
            className="mt-2 h-0.5 w-full accent-vermilion"
            aria-label="导入进度"
            {...(processing ? {} : { value: 1, max: 1 })}
          />
          <p className="mt-2 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
            状态：{batch.data?.status ?? "…"}
          </p>
          {batch.isError ? (
            <p role="alert" className="mt-2 text-sm text-vermilion">
              读取批次失败：{batch.error instanceof Error ? batch.error.message : "请重试"}
            </p>
          ) : null}
          {batch.data?.errorMessage ? (
            <p className="mt-2 text-sm text-vermilion">{batch.data.errorMessage}</p>
          ) : null}

          {/* 行级失败清单：原型 validation-table 的行式语言，只保留真实存在的「行 / 原因」两列。 */}
          {errors.data?.length ? (
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
                {errors.data.slice(0, 20).map((item) => (
                  <tr key={`${item.lineNumber}-${item.reason}`} className="text-vermilion">
                    <td className="w-9 border-b border-rule py-[13px] pr-3 font-mono text-[10px] text-ink-faint">
                      {item.lineNumber}
                    </td>
                    <td className="border-b border-rule py-[13px] pr-3 text-[13px] leading-[1.6]">
                      {item.reason}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}

          {batch.data?.status === "PAUSED" || batch.data?.status === "FAILED" ? (
            <div className="mt-6 flex justify-end">
              <button
                type="button"
                className="btn primary px-5 py-2.5"
                onClick={() =>
                  resumeImport(batchId).then(
                    () => void batch.refetch(),
                    () => setError("继续导入失败"),
                  )
                }
              >
                继续导入
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
