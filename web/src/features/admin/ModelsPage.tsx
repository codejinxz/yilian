import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { appendAuditEvent } from "@/lib/api/admin";
import {
  AdminField,
  AdminSection,
  Bars,
  BarsCaption,
  LiveMark,
  MetricRow,
  Modal,
  Row,
  RowAct,
  RowList,
  RowText,
  StatePill,
  useToastMessage,
} from "./ui";

/** 模型供应商演示配置；只存在于本页状态，不写库、不调用真实模型。 */
interface ProviderDemo {
  id: string;
  name: string;
  type: string;
  model: string;
  base: string;
  /** 服务端只回掩码；演示里也是掩码，留空则不修改。 */
  keyMasked: string;
  enabled: boolean;
  balance: number | null;
  balanceAuto: boolean;
  models: Array<{ id: string; label: string }>;
}

/** Prompt 模板演示数据；保存为新版本只更新本页状态。 */
interface PromptDemo {
  id: string;
  name: string;
  version: number;
  updated: string;
  enabled: boolean;
  system: string;
  user: string;
}

/** 任务路由演示数据；model 为 `providerId:modelId` 或 `local-rules`。 */
interface RouteDemo {
  task: string;
  model: string;
  note: string;
}

/** 供应商初始演示值，与原型 PROVIDERS 一致。 */
const INITIAL_PROVIDERS: ProviderDemo[] = [
  {
    id: "deepseek",
    name: "DeepSeek",
    type: "OpenAI 兼容",
    model: "deepseek-chat",
    base: "https://api.deepseek.com",
    keyMasked: "sk-••••7f2a",
    enabled: true,
    balance: 42.6,
    balanceAuto: true,
    models: [
      { id: "deepseek-chat", label: "deepseek-chat" },
      { id: "deepseek-reasoner", label: "deepseek-reasoner" },
      { id: "deepseek-v4.1-flash", label: "v4.1-flash · 2026-10-17" },
    ],
  },
  {
    id: "ollama",
    name: "Ollama（本地）",
    type: "本地模型",
    model: "qwen2.5:7b",
    base: "http://localhost:11434/v1",
    keyMasked: "—",
    enabled: true,
    balance: null,
    balanceAuto: false,
    models: [
      { id: "qwen2.5:7b", label: "qwen2.5:7b" },
      { id: "qwen2.5:14b", label: "qwen2.5:14b" },
      { id: "llama3.1:8b", label: "llama3.1:8b" },
    ],
  },
  {
    id: "openai",
    name: "OpenAI",
    type: "OpenAI",
    model: "gpt-4.1-mini",
    base: "https://api.openai.com/v1",
    keyMasked: "sk-••••3c91",
    enabled: false,
    balance: null,
    balanceAuto: false,
    models: [
      { id: "gpt-4.1-mini", label: "gpt-4.1-mini" },
      { id: "gpt-4.1", label: "gpt-4.1" },
      { id: "o4-mini", label: "o4-mini" },
    ],
  },
];

/** 任务路由初始演示值。 */
const INITIAL_ROUTES: RouteDemo[] = [
  { task: "语块填空批改", model: "local-rules", note: "本地确定性判定，零成本零延迟" },
  { task: "整句翻译批改", model: "deepseek:deepseek-chat", note: "五维评分 + 逐句证据" },
  { task: "申诉裁决", model: "deepseek:deepseek-reasoner", note: "只判断语义等价" },
  { task: "改写建议", model: "deepseek:deepseek-chat", note: "生成更地道的表达" },
];

/** Prompt 模板初始演示值。 */
const INITIAL_PROMPTS: PromptDemo[] = [
  {
    id: "cloze",
    name: "语块填空批改",
    version: 3,
    updated: "10-21",
    enabled: true,
    system: "你是英语写作教师。按语块是否命中判分，只输出 JSON，不要解释。",
    user: "中文：{{zh}}\n参考译文：{{ref}}\n语块：{{chunks}}\n学生答案：{{answer}}\n\n请逐槽判断命中、缺失或错误，并给出分数。",
  },
  {
    id: "free",
    name: "整句翻译批改",
    version: 5,
    updated: "10-19",
    enabled: true,
    system:
      "你是专八翻译阅卷人。按五维评分：信息完整 30、语法 20、搭配 20、句法 20、语域 10。只输出 JSON。",
    user: "中文：{{zh}}\n参考译文：{{ref}}\n评分标准：{{rubric}}\n学生译文：{{answer}}\n\n每维给 score 与 evidence，并给出 minimal_fix 与 next_drill。",
  },
  {
    id: "appeal",
    name: "申诉裁决",
    version: 2,
    updated: "10-17",
    enabled: true,
    system: '你判断学生的译法是否与参考译文语义等价。只输出 JSON：{"accept":true,"reason":"..."}',
    user: "中文：{{zh}}\n参考译文：{{ref}}\n学生答案：{{answer}}\n\n只判断语义是否等价，不评价风格。",
  },
];

/** Prompt 模板可注入的变量。 */
const PROMPT_VARS = ["{{zh}}", "{{ref}}", "{{chunks}}", "{{answer}}", "{{rubric}}", "{{tags}}"];

/** 近 14 天用量演示数据：确定性伪随机，避免每次渲染跳动。 */
const LLM_USAGE = (() => {
  let seed = 4242;
  const random = () => {
    seed = (seed * 1664525 + 1013904223) % 4294967296;
    return seed / 4294967296;
  };
  return Array.from({ length: 14 }, (_, index) => {
    const calls = 3 + Math.floor(random() * 17);
    return {
      calls,
      tokens: calls * (520 + Math.floor(random() * 480)),
      back: 13 - index,
    };
  });
})();

/** 试运行示例输出；固定 JSON，演示模板渲染结果。 */
const TRIAL_OUTPUT =
  '{\n  "score": 78,\n  "coverage": [{ "id": "more_and_more", "status": "hit" }],\n  "minimal_fix": "increasingly 更紧，more and more 偏口语",\n  "next_drill": "再练 2 句「副词结构」",\n  "tokens": 486\n}';

/** 原型 .prompt-area：等宽 12px、surface 底、1px 分隔线。 */
function promptAreaClassName(): string {
  return "w-full resize-y rounded-[2px] border border-rule bg-surface p-3 font-mono text-xs leading-[1.7] text-ink outline-none transition focus:border-ink-faint";
}

/** 原型 .field input：无边框只有底部分隔线。 */
function fieldInputClassName(): string {
  return "w-full border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition focus:border-vermilion";
}

/**
 * 模型页签：供应商卡（原型 .model-card）、任务路由、Prompt 模板、本月用量与预算。
 * 页头注明演示数据，不调用真实模型；所有配置只改本页状态，写操作走「填原因」并写演示审计。
 */
export function ModelsPage() {
  const { show: toast, node: toastNode } = useToastMessage();
  const [providers, setProviders] = useState(INITIAL_PROVIDERS);
  const [routes, setRoutes] = useState(INITIAL_ROUTES);
  const [prompts, setPrompts] = useState(INITIAL_PROMPTS);
  const [configuring, setConfiguring] = useState<string | null>(null);
  const [editingPrompt, setEditingPrompt] = useState<string | null>(null);
  const [budget, setBudget] = useState("30");
  const [trialOutput, setTrialOutput] = useState<string | null>(null);

  // 演示审计：动作与理由写进 mock 审计日志；真实后端没有该端点时静默忽略。
  const auditMutation = useMutation({ mutationFn: appendAuditEvent });

  /** 记一条演示审计，失败不打扰演示。 */
  function audit(action: string, targetId: string, reason: string) {
    auditMutation.mutate({ action, targetType: "MODEL", targetId, reason });
  }

  const configuringProvider = providers.find((provider) => provider.id === configuring) ?? null;
  const editingPromptItem = prompts.find((prompt) => prompt.id === editingPrompt) ?? null;

  const calls = LLM_USAGE.reduce((sum, item) => sum + item.calls, 0);
  const tokens = LLM_USAGE.reduce((sum, item) => sum + item.tokens, 0);
  const deepseek = providers.find((provider) => provider.id === "deepseek");
  const localRules = { id: "local-rules", label: "本地规则（不调模型）" };
  const routeOptions = [
    localRules,
    ...providers
      .filter((provider) => provider.enabled)
      .flatMap((provider) =>
        provider.models.map((model) => ({
          id: `${provider.id}:${model.id}`,
          label: `${provider.name} · ${model.label}`,
        })),
      ),
  ];
  const routeLabel = (model: string) =>
    routeOptions.find((option) => option.id === model)?.label ?? model;

  return (
    <div>
      <p className="font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
        演示数据，不调用真实模型。配置存数据库、保存即热更新，不用改代码重启；API Key
        加密存储、只写不回显，改动写入审计。（以上为上线后行为，本页为演示态）
      </p>

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {providers.map((provider) => (
          <div
            key={provider.id}
            className={
              provider.enabled
                ? "rounded-[2px] border border-moss/40 p-[18px]"
                : "rounded-[2px] border border-rule p-[18px]"
            }
          >
            <div className="flex items-center justify-between gap-2.5">
              <span className="text-[13px] text-ink">{provider.name}</span>
              <LiveMark on={provider.enabled}>{provider.enabled ? "启用" : "停用"}</LiveMark>
            </div>
            <p className="mt-2 font-mono text-[10px] leading-[1.6] tracking-[0.04em] text-ink-faint">
              {provider.type}
              <br />
              {provider.base}
            </p>
            <select
              aria-label={`${provider.name} 模型`}
              value={provider.model}
              onChange={(event) => {
                const model = event.target.value;
                setProviders((previous) =>
                  previous.map((item) => (item.id === provider.id ? { ...item, model } : item)),
                );
                audit("MODEL_CONFIG_UPDATE", `${provider.name} · ${model}`, "切换模型");
                toast(`${provider.name} → ${model}`);
              }}
              className="mt-3.5 w-full rounded-[2px] border border-rule bg-transparent px-2 py-[7px] font-mono text-[11px] text-ink-muted outline-none transition focus:border-ink-faint"
            >
              {provider.models.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.label}
                </option>
              ))}
            </select>
            <p className="mt-[18px] font-serif text-xl text-ink">
              {provider.balance !== null ? `¥${provider.balance.toFixed(2)}` : "—"}
              <small className="ml-[5px] font-mono text-[10px] text-ink-faint">
                {provider.balanceAuto
                  ? "自动查询"
                  : provider.balance !== null
                    ? "手动"
                    : "未开放接口"}
              </small>
            </p>
            <div className="mt-3.5 flex flex-wrap gap-3.5">
              <button
                type="button"
                className="linkish text-[11px]"
                onClick={() => setConfiguring(provider.id)}
              >
                配置
              </button>
              <button
                type="button"
                className="linkish text-[11px]"
                onClick={() =>
                  toast(
                    provider.enabled
                      ? `连接正常 · ${provider.id === "ollama" ? 180 : 820}ms（演示）`
                      : "模型已停用，先启用再测试",
                  )
                }
              >
                测试连接
              </button>
              <button
                type="button"
                className="linkish text-[11px]"
                onClick={() => {
                  setProviders((previous) =>
                    previous.map((item) =>
                      item.id === provider.id ? { ...item, enabled: !item.enabled } : item,
                    ),
                  );
                  audit("MODEL_TOGGLE", provider.name, provider.enabled ? "停用" : "启用");
                  toast(provider.enabled ? "已停用（演示）" : "已启用（演示）");
                }}
              >
                {provider.enabled ? "停用" : "启用"}
              </button>
            </div>
          </div>
        ))}
      </div>

      <p className="mt-4 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
        配置存数据库、保存即热更新，不用改代码重启。API Key 加密存储，只写不回显，改动写入审计。
      </p>

      <AdminSection title="任务路由">
        <RowList>
          {routes.map((route) => (
            <Row key={route.task}>
              <RowText main={route.task} meta={route.note} />
              <StatePill tone={route.model === "local-rules" ? "ok" : "muted"}>
                {route.model === "local-rules" ? "本地" : "模型"}
              </StatePill>
              <select
                aria-label={`${route.task} 路由`}
                value={route.model}
                onChange={(event) => {
                  const model = event.target.value;
                  setRoutes((previous) =>
                    previous.map((item) => (item.task === route.task ? { ...item, model } : item)),
                  );
                  audit("MODEL_CONFIG_UPDATE", `${route.task} · ${model}`, "修改任务路由");
                  toast(`「${route.task}」已改走 ${routeLabel(model)}（仅演示状态）`);
                }}
                className="max-w-[230px] rounded-[2px] border border-rule bg-transparent px-2.5 py-[7px] font-mono text-[11px] text-ink-muted outline-none transition focus:border-ink-faint"
              >
                {routeOptions.map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.label}
                  </option>
                ))}
              </select>
            </Row>
          ))}
        </RowList>
      </AdminSection>

      <AdminSection title="Prompt 模板">
        <RowList>
          {prompts.map((prompt) => (
            <Row key={prompt.id}>
              <RowText main={prompt.name} meta={`v${prompt.version} · 更新于 ${prompt.updated}`} />
              <StatePill tone={prompt.enabled ? "ok" : "muted"}>
                {prompt.enabled ? "启用" : "停用"}
              </StatePill>
              <RowAct onClick={() => setEditingPrompt(prompt.id)}>编辑</RowAct>
            </Row>
          ))}
        </RowList>
        <p className="mt-3.5 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
          变量在调用时注入；保存即新版本，可回滚。用户答案一律当数据，不当指令。
        </p>
      </AdminSection>

      <AdminSection title="本月用量">
        <MetricRow
          items={[
            { value: calls, label: "近 14 天请求" },
            { value: `${Math.round(tokens / 1000)}k`, label: "Tokens" },
            { value: `¥${(calls * 0.012).toFixed(1)}`, label: "估算成本" },
            {
              value:
                deepseek?.balance !== null && deepseek ? `¥${deepseek.balance.toFixed(1)}` : "—",
              label: "账户余额",
            },
          ]}
        />
        <div className="mt-6">
          <Bars
            values={LLM_USAGE.map((item) => item.calls)}
            titles={LLM_USAGE.map((item) => `${item.calls} 次 / ${item.tokens} tokens`)}
          />
          <BarsCaption left="14 天前" right="今天" />
        </div>
      </AdminSection>

      <AdminSection title="预算">
        <div className="flex flex-wrap items-center gap-3">
          <label htmlFor="budget-input" className="text-xs text-ink-muted">
            月度上限
          </label>
          <input
            id="budget-input"
            type="number"
            min={0}
            step={1}
            value={budget}
            onChange={(event) => setBudget(event.target.value)}
            className="w-[110px] min-w-[110px] border-b border-rule bg-transparent py-2 text-[13px] text-ink outline-none transition focus:border-vermilion"
          />
          <span className="font-mono text-[11px] text-ink-faint">元</span>
          <button
            type="button"
            className="btn ghost"
            onClick={() => {
              const value = Number(budget);
              if (!Number.isFinite(value) || value < 0) {
                toast("填一个有效金额");
                return;
              }
              toast(`月度上限已设为 ¥${value}（演示）`);
            }}
          >
            保存
          </button>
        </div>
        <p className="mt-3 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
          到 80% 提醒，到 100% 自动降级为本地批改，不会静默扣费。
        </p>
      </AdminSection>

      {configuringProvider ? (
        <ProviderConfigModal
          key={configuringProvider.id}
          provider={configuringProvider}
          onClose={() => setConfiguring(null)}
          toast={toast}
          audit={audit}
          onSaved={(next) =>
            setProviders((previous) => previous.map((item) => (item.id === next.id ? next : item)))
          }
        />
      ) : null}

      {editingPromptItem ? (
        <PromptEditModal
          key={editingPromptItem.id}
          prompt={editingPromptItem}
          onClose={() => {
            setEditingPrompt(null);
            setTrialOutput(null);
          }}
          toast={toast}
          audit={audit}
          trialOutput={trialOutput}
          onTrial={() => setTrialOutput(TRIAL_OUTPUT)}
          onSaved={(next) => {
            setPrompts((previous) => previous.map((item) => (item.id === next.id ? next : item)));
            setEditingPrompt(null);
            setTrialOutput(null);
          }}
        />
      ) : null}

      {toastNode}
    </div>
  );
}

/** 供应商配置弹窗：Base URL / 模型 / API Key（掩码占位）+ 测试连接。 */
function ProviderConfigModal({
  provider,
  onClose,
  onSaved,
  toast,
  audit,
}: {
  provider: ProviderDemo;
  onClose: () => void;
  onSaved: (provider: ProviderDemo) => void;
  toast: (message: string) => void;
  audit: (action: string, targetId: string, reason: string) => void;
}) {
  const [base, setBase] = useState(provider.base);
  const [model, setModel] = useState(provider.model);
  const [key, setKey] = useState("");
  return (
    <Modal open onClose={onClose} kicker={`模型配置 · ${provider.type}`} title={provider.name}>
      <div className="mt-4">
        <AdminField label="Base URL">
          <input
            value={base}
            onChange={(event) => setBase(event.target.value)}
            aria-label="Base URL"
            className={fieldInputClassName()}
          />
        </AdminField>
        <AdminField label="模型（可从目录选，也可手填）">
          <input
            list="provider-model-list"
            value={model}
            onChange={(event) => setModel(event.target.value)}
            aria-label="模型"
            className={fieldInputClassName()}
          />
          <datalist id="provider-model-list">
            {provider.models.map((item) => (
              <option key={item.id} value={item.id}>
                {item.label}
              </option>
            ))}
          </datalist>
        </AdminField>
        <AdminField label="API Key">
          <input
            type="password"
            placeholder={provider.keyMasked === "—" ? "本地模型不需要" : "已保存，留空则不修改"}
            value={key}
            onChange={(event) => setKey(event.target.value)}
            aria-label="API Key"
            className={fieldInputClassName()}
          />
        </AdminField>
        <p className="font-mono text-[10px] leading-[1.6] tracking-[0.04em] text-ink-faint">
          密钥加密存储，接口只返回掩码；保存后立即生效，无需重启。（演示：不会真实保存）
        </p>
        <div className="mt-3">
          <button
            type="button"
            className="linkish"
            onClick={() => toast(`已从 /models 拉取 ${provider.models.length} 个模型（演示）`)}
          >
            从 /models 刷新目录
          </button>
        </div>
        <div className="mt-[22px] flex justify-between gap-3">
          <button
            type="button"
            className="btn ghost"
            onClick={() =>
              toast(
                provider.enabled
                  ? `连接正常 · ${provider.id === "ollama" ? 180 : 820}ms（演示）`
                  : "模型已停用，先启用再测试",
              )
            }
          >
            测试连接
          </button>
          <div className="flex gap-3">
            <button type="button" className="btn ghost" onClick={onClose}>
              取消
            </button>
            <button
              type="button"
              className="btn primary"
              onClick={() => {
                onSaved({
                  ...provider,
                  base: base.trim() || provider.base,
                  model: model.trim() || provider.model,
                });
                audit("MODEL_CONFIG_UPDATE", `${provider.name} · ${model}`, "模型配置");
                onClose();
                toast("已保存并热更新，无需重启（演示）");
              }}
            >
              保存
            </button>
          </div>
        </div>
      </div>
    </Modal>
  );
}

/** Prompt 编辑宽弹窗：系统提示词 + 用户模板 + 变量插入 + 试运行 + 保存为新版本。 */
function PromptEditModal({
  prompt,
  onClose,
  onSaved,
  onTrial,
  trialOutput,
  toast,
  audit,
}: {
  prompt: PromptDemo;
  onClose: () => void;
  onSaved: (prompt: PromptDemo) => void;
  onTrial: () => void;
  trialOutput: string | null;
  toast: (message: string) => void;
  audit: (action: string, targetId: string, reason: string) => void;
}) {
  const [system, setSystem] = useState(prompt.system);
  const [user, setUser] = useState(prompt.user);
  const now = new Date();
  const today = `${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
  return (
    <Modal
      open
      onClose={onClose}
      kicker={`Prompt 模板 · v${prompt.version}`}
      title={prompt.name}
      wide
    >
      <div className="mt-4">
        <label
          htmlFor="prompt-system"
          className="mb-2 mt-[18px] block font-mono text-[9px] tracking-[0.16em] text-ink-faint"
        >
          系统提示词
        </label>
        <textarea
          id="prompt-system"
          rows={3}
          value={system}
          onChange={(event) => setSystem(event.target.value)}
          aria-label="系统提示词"
          className={promptAreaClassName()}
        />
        <label
          htmlFor="prompt-user"
          className="mb-2 mt-[18px] block font-mono text-[9px] tracking-[0.16em] text-ink-faint"
        >
          用户模板
        </label>
        <textarea
          id="prompt-user"
          rows={7}
          value={user}
          onChange={(event) => setUser(event.target.value)}
          aria-label="用户模板"
          className={promptAreaClassName()}
        />
        <div className="mt-2.5 flex flex-wrap gap-2">
          {PROMPT_VARS.map((variable) => (
            <button
              key={variable}
              type="button"
              className="rounded-[2px] border border-rule bg-transparent px-[7px] py-[3px] font-mono text-[10px] text-ink-muted transition-colors hover:border-vermilion hover:text-vermilion"
              onClick={() =>
                setUser(
                  (previous) =>
                    `${previous}${previous && !/\s$/.test(previous) ? " " : ""}${variable}`,
                )
              }
            >
              {variable}
            </button>
          ))}
        </div>
        {trialOutput ? (
          <pre className="mt-3.5 max-h-80 overflow-auto whitespace-pre-wrap break-words border-l-2 border-vermilion bg-surface p-3 font-mono text-[11px] leading-[1.7] text-ink-muted">
            {trialOutput}
          </pre>
        ) : null}
        <div className="mt-[22px] flex justify-between gap-3">
          <button type="button" className="btn ghost" onClick={onTrial}>
            试运行
          </button>
          <div className="flex gap-3">
            <button type="button" className="btn ghost" onClick={onClose}>
              取消
            </button>
            <button
              type="button"
              className="btn primary"
              onClick={() => {
                const version = prompt.version + 1;
                onSaved({
                  ...prompt,
                  system: system.trim(),
                  user: user.trim(),
                  version,
                  updated: today,
                });
                audit("PROMPT_UPDATE", `${prompt.name} · v${version}`, "Prompt 模板更新");
                toast(`已保存为 v${version}，下一请求生效（演示）`);
              }}
            >
              保存为新版本
            </button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
