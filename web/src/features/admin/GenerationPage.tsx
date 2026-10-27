import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import {
  getAdminBanks,
  getImportBatches,
  importGeneratedSentences,
  rollbackImport,
} from "@/lib/api/admin";
import { isMockEnabled } from "@/lib/mock-flag";
import { queryKeys } from "@/lib/query";
import { GEN_TAGS, LEVELS, levelLabel, MOCK_GEN_MODEL_LABEL, relativeDayLabel } from "./constants";
import { buildGenPrompt, type GenValidation, validatePastedGeneration } from "./gen";
import {
  AdminField,
  AdminSection,
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

/** 「填原因」弹窗的一次请求。 */
interface ReasonRequest {
  title: string;
  description: string;
  options: string[];
  onConfirm: (reason: string) => void;
}

/** 原型 .prompt-area：等宽 12px、surface 底、1px 分隔线，Prompt 与粘贴框共用。 */
function promptAreaClassName(): string {
  return "w-full resize-y rounded-[2px] border border-rule bg-surface p-3 font-mono text-xs leading-[1.7] text-ink outline-none transition placeholder:text-ink-faint focus:border-ink-faint";
}

/** 原型 .var 考点片：10px mono 小片，选中转朱红加浅底。 */
function tagChipClassName(on: boolean): string {
  return on
    ? "rounded-[2px] border border-vermilion bg-[rgba(178,58,46,0.06)] px-[7px] py-[3px] font-mono text-[10px] text-vermilion"
    : "rounded-[2px] border border-rule bg-transparent px-[7px] py-[3px] font-mono text-[10px] text-ink-muted transition-colors hover:border-vermilion hover:text-vermilion";
}

/** 「用当前模型生成」的演示样本；结构就是 Prompt 约定的 JSON，可直接通过校验。 */
const GEN_SAMPLE = [
  {
    zh: "随着人们生活水平的提高，越来越多的人开始关注健康。",
    ref: "As living standards improve, more and more people are paying attention to their health.",
    chunks: [
      { zh: "随着人们生活水平的提高", answers: ["As living standards improve"] },
      { zh: "越来越多的人", answers: ["more and more people"] },
      { zh: "开始关注", answers: ["are paying attention to"] },
      { zh: "健康", answers: ["their health"] },
    ],
    blank: [1, 2],
    tags: ["搭配", "时态"],
    level: "CET4",
    note: "As 引导时间状语从句；more and more 作定语；pay attention to 后接名词。",
  },
  {
    zh: "出国旅行前，最好提前了解当地的风俗习惯。",
    ref: "Before traveling abroad, you had better learn about the local customs in advance.",
    chunks: [
      { zh: "出国旅行前", answers: ["Before traveling abroad"] },
      { zh: "最好", answers: ["you had better"] },
      { zh: "了解", answers: ["learn about"] },
      { zh: "当地的风俗习惯", answers: ["the local customs"] },
      { zh: "提前", answers: ["in advance"] },
    ],
    blank: [1, 3],
    tags: ["搭配"],
    level: "CET4",
    note: "had better 后接动词原形；in advance 是固定副词短语，放句末。",
  },
  {
    zh: "高中阶段养成的时间管理习惯，会让人受益终生。",
    ref: "The time management habits formed in high school will benefit you for a lifetime.",
    chunks: [
      {
        zh: "高中阶段养成的时间管理习惯",
        answers: ["The time management habits formed in high school"],
      },
      { zh: "会让人", answers: ["will benefit you"] },
      { zh: "受益终生", answers: ["for a lifetime"] },
    ],
    blank: [1, 2],
    tags: ["从句", "搭配"],
    level: "CET4",
    note: "formed in high school 是过去分词短语作后置定语；benefit sb for a lifetime。",
  },
];

/** 校验结果表（原型 .validation-table）：行 / 中文 / 解析 / 校验结果。 */
function ValidationTable({ validation }: { validation: GenValidation }) {
  return (
    <table className="mt-5 w-full border-collapse">
      <thead>
        <tr>
          {["行", "中文", "解析", "校验结果"].map((head) => (
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
        {validation.rows.map((row) => (
          <tr key={row.row} className={row.ok ? undefined : "text-vermilion"}>
            <td className="w-9 border-b border-rule py-[13px] pr-3 font-mono text-[10px] text-ink-faint">
              {row.row}
            </td>
            <td className="border-b border-rule py-[13px] pr-3 text-[13px] leading-[1.6] text-ink-muted">
              {row.zh}
            </td>
            <td className="border-b border-rule py-[13px] pr-3 text-[13px] leading-[1.6] text-ink-muted">
              {row.note || <span className="text-vermilion">缺解析</span>}
            </td>
            <td className="border-b border-rule py-[13px] pr-3 text-[13px] leading-[1.6]">
              {row.ok ? <span className="text-moss">通过</span> : row.why}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * 出题页签：左右两栏需求表单与 Prompt 预览、粘贴 JSON 前端校验、导入与最近批次回滚。
 * 全程不调用真实模型：「用当前模型生成」填入演示样本并明确标注。
 */
export function GenerationPage() {
  const { show: toast, node: toastNode } = useToastMessage();
  // mock 模式下点明演示模型（与模型页目录同源）；真实模式回退通用文案，不假装知道模型名。
  const generateLabel = isMockEnabled() ? `用 ${MOCK_GEN_MODEL_LABEL} 生成` : "用当前模型生成";
  const queryClient = useQueryClient();
  const [target, setTarget] = useState("");
  const [levelId, setLevelId] = useState("CET4");
  const [count, setCount] = useState("20");
  const [tags, setTags] = useState<string[]>(["搭配"]);
  const [extra, setExtra] = useState("");
  const [prompt, setPrompt] = useState("");
  const [promptMeta, setPromptMeta] = useState("");
  const [paste, setPaste] = useState("");
  const [validation, setValidation] = useState<GenValidation | null>(null);
  const [targetBankId, setTargetBankId] = useState("");
  const [reasonRequest, setReasonRequest] = useState<ReasonRequest | null>(null);

  const banksQuery = useQuery({
    queryKey: queryKeys.admin.banks({ scope: "gen", size: 100 }),
    queryFn: ({ signal }) => getAdminBanks({ page: 1, size: 100 }, signal),
  });
  const banks = (banksQuery.data?.items ?? []).filter((bank) => bank.status !== "DISABLED");
  const effectiveBankId = targetBankId || banks[1]?.id || banks[0]?.id || "";
  const bankName = (id: string) => banks.find((bank) => bank.id === id)?.name ?? id;

  const batchesQuery = useQuery({
    queryKey: queryKeys.admin.imports({ page: 1, size: 5 }),
    queryFn: ({ signal }) => getImportBatches({ page: 1, size: 5 }, signal),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["admin", "sentences"] });
    void queryClient.invalidateQueries({ queryKey: ["admin", "banks"] });
    void queryClient.invalidateQueries({ queryKey: ["admin", "imports"] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.imports({}) });
    void queryClient.invalidateQueries({ queryKey: ["admin", "overview"] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.auditLogs({}) });
  }

  const importMutation = useMutation({
    mutationFn: (input: { bankId: string; items: Array<Record<string, unknown>> }) =>
      importGeneratedSentences({ ...input, reason: "AI 生成导入" }),
    onSuccess: (batch) => {
      invalidate();
      setPaste("");
      setValidation(null);
      toast(`已导入 ${batch.importedRows} 句到「${bankName(batch.bankId)}」，切过去就能练`);
    },
    onError: (error) => toast(error instanceof Error ? error.message : "导入失败"),
  });
  const rollbackMutation = useMutation({
    mutationFn: (input: { batchId: string; reason: string }) =>
      rollbackImport(input.batchId, input.reason),
    onSuccess: () => {
      invalidate();
      toast("已回滚，导入的句子进入回收站");
    },
    onError: (error) => toast(error instanceof Error ? error.message : "回滚失败"),
  });

  /** 演示「用当前模型生成」：不调用模型，900ms 后填入样本并自动校验。 */
  function fakeModelGenerate() {
    toast(`正在用 ${MOCK_GEN_MODEL_LABEL} 生成…（演示，不调用真实模型）`);
    window.setTimeout(() => {
      const sample = JSON.stringify(GEN_SAMPLE, null, 2);
      setPaste(sample);
      setValidation(validatePastedGeneration(sample));
      toast("已生成 3 句，请校验后导入");
    }, 900);
  }

  async function copyPrompt() {
    if (!prompt) {
      toast("先生成 Prompt");
      return;
    }
    try {
      await navigator.clipboard.writeText(prompt);
      toast("已复制到剪贴板");
    } catch {
      toast("复制失败，请手动全选");
    }
  }

  return (
    <div>
      <p className="font-mono text-[11px] tracking-[0.06em] text-ink-faint">
        演示数据，不调用真实模型：「{generateLabel}」填入固定样本，导入与回滚走真实 mock 流程。
      </p>

      <div className="mt-6 grid gap-10 lg:grid-cols-2">
        <div>
          <p className="section-title mb-4">出题需求</p>
          <AdminField label="目标 / 场景">
            <input
              placeholder="例：大学生英语四级、出国旅游、高中英语"
              value={target}
              onChange={(event) => setTarget(event.target.value)}
              aria-label="目标 / 场景"
              className="w-full border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition placeholder:text-ink-faint focus:border-vermilion"
            />
          </AdminField>
          <div className="grid grid-cols-2 gap-4">
            <AdminField label="难度">
              <AdminSelect
                value={levelId}
                onChange={(event) => setLevelId(event.target.value)}
                aria-label="难度"
              >
                {LEVELS.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.label}
                  </option>
                ))}
              </AdminSelect>
            </AdminField>
            <AdminField label="数量">
              <AdminSelect
                value={count}
                onChange={(event) => setCount(event.target.value)}
                aria-label="数量"
              >
                <option value="10">10 句</option>
                <option value="20">20 句</option>
                <option value="30">30 句</option>
              </AdminSelect>
            </AdminField>
          </div>
          <AdminField label="考点">
            <div className="flex flex-wrap gap-2">
              {GEN_TAGS.map((tag) => {
                const on = tags.includes(tag);
                return (
                  <button
                    key={tag}
                    type="button"
                    aria-pressed={on}
                    className={tagChipClassName(on)}
                    onClick={() =>
                      setTags((previous) =>
                        previous.includes(tag)
                          ? previous.filter((item) => item !== tag)
                          : [...previous, tag],
                      )
                    }
                  >
                    {tag}
                  </button>
                );
              })}
            </div>
          </AdminField>
          <AdminField label="补充要求（可选）">
            <textarea
              rows={3}
              placeholder="例：偏商务场景，句子短一点，少用生僻词"
              value={extra}
              onChange={(event) => setExtra(event.target.value)}
              aria-label="补充要求"
              className={promptAreaClassName()}
            />
          </AdminField>
          <div className="mt-8 flex gap-3.5">
            <button
              type="button"
              className="btn primary"
              onClick={() => {
                const text = buildGenPrompt({
                  target,
                  levelId,
                  levelLabel: levelLabel(levelId),
                  count,
                  tags,
                  extra,
                });
                setPrompt(text);
                setPromptMeta(`${text.length} 字 · 约 ${Math.round(text.length / 2)} tokens`);
                toast("Prompt 已生成，可以复制");
              }}
            >
              生成 Prompt
            </button>
            <button type="button" className="btn ghost" onClick={fakeModelGenerate}>
              {generateLabel}
            </button>
          </div>
        </div>

        <div>
          <p className="section-title mb-4">Prompt 预览</p>
          <textarea
            readOnly
            rows={18}
            className={promptAreaClassName()}
            placeholder="填好左侧，点「生成 Prompt」"
            value={prompt}
            onChange={() => {}}
            aria-label="Prompt 预览"
          />
          <div className="mt-3 flex flex-wrap items-center justify-between gap-3.5">
            <span className="hintline">{promptMeta}</span>
            <button type="button" className="btn ghost" onClick={() => void copyPrompt()}>
              复制
            </button>
          </div>
          <p className="mt-3.5 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
            复制到任意免费 AI，拿到 JSON 后粘贴到下方校验。也可以点「{generateLabel}」直接出题。
          </p>
        </div>
      </div>

      <AdminSection title="粘贴 AI 返回的 JSON">
        <textarea
          rows={6}
          className={promptAreaClassName()}
          placeholder='[{"zh":"…","ref":"…","chunks":[…],"blank":[1],"tags":["搭配"],"level":"CET4"}]'
          value={paste}
          onChange={(event) => setPaste(event.target.value)}
          aria-label="粘贴 JSON"
        />
        <div className="mt-3 flex flex-wrap items-center justify-between gap-3.5">
          <button
            type="button"
            className="btn ghost"
            onClick={() => {
              setValidation(validatePastedGeneration(paste));
            }}
          >
            校验并预览
          </button>
          <span className="hintline">
            {validation
              ? `解析 ${validation.rows.length} 行 · ${validation.valid.length} 行通过`
              : ""}
          </span>
        </div>

        {validation ? (
          <>
            <ValidationTable validation={validation} />

            <div className="mt-4 flex items-center gap-2.5 text-xs text-ink-muted">
              <span>导入到</span>
              <AdminSelect
                value={effectiveBankId}
                onChange={(event) => setTargetBankId(event.target.value)}
                aria-label="导入目标题库"
              >
                {banks.map((bank) => (
                  <option key={bank.id} value={bank.id}>
                    {bank.name}
                  </option>
                ))}
              </AdminSelect>
            </div>
            <div className="mt-6 flex flex-wrap items-center justify-between gap-5">
              <span className="font-mono text-[11px] tracking-[0.06em] text-ink-faint">
                {validation.valid.length} 行通过
              </span>
              <button
                type="button"
                className="btn primary"
                disabled={validation.valid.length === 0 || importMutation.isPending}
                onClick={() =>
                  importMutation.mutate({ bankId: effectiveBankId, items: validation.valid })
                }
              >
                导入通过的句子
              </button>
            </div>
          </>
        ) : null}
      </AdminSection>

      <AdminSection title="最近导入">
        {batchesQuery.isPending ? (
          <LoadingBlock label="正在载入批次…" />
        ) : batchesQuery.isError ? (
          <ErrorState error={batchesQuery.error} onRetry={() => void batchesQuery.refetch()} />
        ) : (
          <RowList>
            {batchesQuery.data.items.length === 0 ? (
              <EmptyRow>还没有导入批次。用模型生成或粘贴导入后会出现在这里。</EmptyRow>
            ) : (
              batchesQuery.data.items.map((batch) => (
                <Row key={batch.batchId}>
                  <RowText
                    main={`${batch.batchId.slice(0, 8)} · ${bankName(batch.bankId)}`}
                    meta={`${batch.importedRows || batch.validRows} 句 · ${relativeDayLabel(batch.createdAt ?? "")}${batch.originalFilename ? ` · ${batch.originalFilename}` : ""}`}
                  />
                  <StatePill tone={batch.status === "ROLLED_BACK" ? "warn" : "ok"}>
                    {batch.status === "ROLLED_BACK" ? "已回滚" : "已提交"}
                  </StatePill>
                  {batch.status === "COMMITTED" ? (
                    <RowAct
                      disabled={rollbackMutation.isPending}
                      onClick={() =>
                        setReasonRequest({
                          title: "回滚导入批次",
                          description: `将把批次 ${batch.batchId.slice(0, 8)} 导入的 ${batch.importedRows} 句移入回收站；审计会记录原因。`,
                          options: ["内容有误", "误导入", "其他"],
                          onConfirm: (reason) =>
                            rollbackMutation.mutate({ batchId: batch.batchId, reason }),
                        })
                      }
                    >
                      回滚
                    </RowAct>
                  ) : (
                    <span aria-hidden="true" />
                  )}
                </Row>
              ))
            )}
          </RowList>
        )}
      </AdminSection>

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
