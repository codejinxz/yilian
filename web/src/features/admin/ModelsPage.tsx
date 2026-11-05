import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { ConfirmDialog } from "@/features/settings/confirm-dialog";
import {
  activateLlmProvider,
  createLlmProvider,
  deleteLlmProvider,
  getLlmConfig,
  getLlmProviders,
  type LlmProtocol,
  type LlmProvider,
  updateLlmConfig,
  updateLlmProvider,
} from "@/lib/api/admin";
import { queryKeys } from "@/lib/query";
import {
  AdminField,
  AdminSection,
  Bars,
  BarsCaption,
  Drawer,
  LiveMark,
  MetricRow,
  Row,
  RowList,
  RowText,
  StatePill,
  useToastMessage,
} from "./ui";

/**
 * 模型页签（provider 目录管理）。
 *
 * <p>架构口径：模型凭据只走环境变量（密钥不入库）。provider 目录
 * （app_settings 键 llm.providers/llm.active）管理多协议多 provider（CC Switch 式）：
 * 本页做元数据 CRUD 与激活切换，密钥按 id 经 YILIAN_LLM_KEY_&lt;大写ID&gt; 注入（UI 只显
 * hasKey 徽标）；目录为空时回退 legacy 单 provider（YILIAN_LLM_* 环境变量直配，只读展示）。
 * 激活的 provider 即答疑等 AI 功能的数据源；llm.tutor 开关独立沿用。
 * mock 口径：本页直连真实端点，mock 层不新增演示路径。
 */

/** 任务路由原型占位值（与原型 index.html 一致）；M3 任务路由域落地前仅作展示参考。 */
const M3_ROUTES = [
  { task: "语块填空批改", model: "local-rules", note: "本地确定性判定，零成本零延迟" },
  { task: "整句翻译批改", model: "deepseek:deepseek-chat", note: "五维评分 + 逐句证据" },
  { task: "申诉裁决", model: "deepseek:deepseek-reasoner", note: "只判断语义等价" },
  { task: "改写建议", model: "deepseek:deepseek-chat", note: "生成更地道的表达" },
] as const;

/** Prompt 模板原型占位值；版本管理随 M3 prompt_versions 域落地，当前只读。 */
const M3_PROMPTS = [
  {
    id: "cloze",
    name: "语块填空批改",
    version: 3,
    updated: "10-21",
    enabled: true,
  },
  {
    id: "free",
    name: "整句翻译批改",
    version: 5,
    updated: "10-19",
    enabled: true,
  },
  {
    id: "appeal",
    name: "申诉裁决",
    version: 2,
    updated: "10-17",
    enabled: true,
  },
] as const;

/** AI 功能清单的将来功能插槽：只做占位展示，落地排期随 M3 计划。 */
const M3_FEATURES = [
  { id: "free-grading", name: "整句翻译批改（AI 增强）", meta: "五维评分 + 逐句证据" },
  { id: "appeal", name: "申诉裁决", meta: "只判断语义等价，不评价风格" },
  { id: "rewrite", name: "改写建议", meta: "生成更地道的表达" },
] as const;

/** 近 14 天用量原型示例：确定性伪随机，仅作 M3 用量观测区块的视觉占位，非真实数据。 */
const M3_USAGE = (() => {
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

/** 协议下拉与徽标的展示文案（wire 值保持原样，附中文名便于识别）。 */
const PROTOCOL_LABELS: Record<LlmProtocol, string> = {
  "anthropic-messages": "Anthropic Messages",
  "openai-chat": "OpenAI Chat Completions",
  "openai-responses": "OpenAI Responses",
};

/** ZCode 网关的预设模板：一键预填，密钥仍需按 id 配 env。 */
const ZCODE_PRESET = {
  id: "zcode-gateway",
  name: "ZCode 网关",
  protocol: "anthropic-messages" as LlmProtocol,
  baseUrl: "http://127.0.0.1:47860/gateway/zcode",
  model: "glm-5.3",
};

/** 新增/编辑表单的字段状态。 */
interface ProviderFormState {
  id: string;
  name: string;
  protocol: LlmProtocol;
  baseUrl: string;
  model: string;
}

/** 统一配置卡里一个「标签 + 值」小格的原型样式。 */
function configCellLabelClassName(): string {
  return "mb-[7px] font-mono text-[9px] tracking-[0.16em] text-ink-faint";
}

/**
 * 答疑关联态的展示文案：enabled 只是开关（app_settings）、
 * credentialsComplete 是凭证按协议口径的齐备性（anthropic-messages 轨 key 可空），缺一功能都不可用。
 * 开关开着但凭证不齐时显示「未生效（缺凭证）」而非笼统的「未开放」，消除管理员视角
 * 「开关是开的、功能却不存在」的矛盾——服务端回 credentialsComplete，前端只做文案归一。
 * @param enabled 管理端开关
 * @param credentialsComplete 凭证按协议口径是否齐备
 * @returns 展示文案
 */
function askLinkageLabel(enabled: boolean, credentialsComplete: boolean): string {
  if (enabled && credentialsComplete) {
    return "启用中";
  }
  return enabled ? "未生效（缺凭证）" : "未开放";
}

/** 表单初始值：新增为空白，编辑回填条目（id 固定不可改）。 */
function formStateFor(provider?: LlmProvider): ProviderFormState {
  return {
    id: provider?.id ?? "",
    name: provider?.name ?? "",
    protocol: provider?.protocol ?? "openai-chat",
    baseUrl: provider?.baseUrl ?? "",
    model: provider?.model ?? "",
  };
}

/** 单条 provider 卡：激活高亮 + 协议/密钥徽标 + 元数据与操作。 */
function ProviderCard({
  provider,
  busy,
  onActivate,
  onEdit,
  onDelete,
}: {
  provider: LlmProvider;
  busy: boolean;
  onActivate: (provider: LlmProvider) => void;
  onEdit: (provider: LlmProvider) => void;
  onDelete: (provider: LlmProvider) => void;
}) {
  return (
    <li
      className={
        provider.active
          ? "list-none rounded-[2px] border border-moss/50 p-4"
          : "list-none rounded-[2px] border border-rule p-4"
      }
      aria-label={`provider ${provider.id}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-baseline gap-2">
          <b className="font-serif text-[14px] font-medium text-ink">{provider.name}</b>
          <span className="font-mono text-[10px] text-ink-faint">{provider.id}</span>
        </span>
        <span className="flex items-center gap-2">
          <StatePill tone="muted">{provider.protocol}</StatePill>
          {provider.hasKey ? (
            <StatePill tone="ok">有 Key</StatePill>
          ) : provider.protocol === "anthropic-messages" ? (
            <StatePill tone="muted">无需 Key</StatePill>
          ) : (
            <StatePill tone="warn">缺 Key</StatePill>
          )}
          {provider.active ? <LiveMark on>激活中</LiveMark> : null}
        </span>
      </div>
      <p className="mt-2 font-mono text-[11px] leading-[1.8] text-ink-muted">
        {provider.baseUrl}
        <span className="mx-2 text-ink-faint">·</span>
        {provider.model}
      </p>
      <div className="mt-3 flex items-center gap-4">
        {!provider.active ? (
          <button
            type="button"
            className="btn ghost"
            disabled={busy}
            aria-label={`激活 provider ${provider.id}`}
            onClick={() => onActivate(provider)}
          >
            激活
          </button>
        ) : null}
        <button
          type="button"
          className="btn ghost"
          disabled={busy}
          aria-label={`编辑 provider ${provider.id}`}
          onClick={() => onEdit(provider)}
        >
          编辑
        </button>
        <button
          type="button"
          className="btn ghost"
          disabled={busy}
          aria-label={`删除 provider ${provider.id}`}
          title={provider.active ? "激活中的 provider 不能删除，请先激活其他条目" : undefined}
          onClick={() => onDelete(provider)}
        >
          删除
        </button>
      </div>
    </li>
  );
}

/**
 * 模型页签：统一模型配置状态卡（激活 provider 生效态）、Provider 目录管理（022，
 * CC Switch 式多 provider：列表/新增/编辑/删除/激活）、AI 功能清单按激活 provider 联动；
 * 下半区任务路由/Prompt 模板/本月用量/预算为 M3 占位（原型参照，只读）。
 * @returns 页签内容
 */
export function ModelsPage() {
  const { show: toast, node: toastNode } = useToastMessage();
  const queryClient = useQueryClient();

  const configQuery = useQuery({
    queryKey: queryKeys.admin.llmConfig,
    queryFn: ({ signal }) => getLlmConfig(signal),
  });
  const providersQuery = useQuery({
    queryKey: queryKeys.admin.llmProviders,
    queryFn: ({ signal }) => getLlmProviders(signal),
  });

  // 表单与删除确认的本地态；form 为 null 时抽屉关闭。
  const [form, setForm] = useState<{ mode: "create" | "edit"; state: ProviderFormState } | null>(
    null,
  );
  const [deleting, setDeleting] = useState<LlmProvider | null>(null);

  /** 目录/配置双失效：激活与 CRUD 都影响 llm/config 的生效视图（服务端同一事实源）。 */
  function invalidateLlmQueries() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.llmProviders });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.llmConfig });
  }

  function onError(error: unknown) {
    toast(error instanceof Error ? error.message : "操作失败，请稍后重试");
  }

  // 启停只受理 enabled；成功失效缓存重新拉取（服务端是开关唯一事实源），失败透出业务文案。
  const toggleMutation = useMutation({
    mutationFn: updateLlmConfig,
    onSuccess: (next) => {
      invalidateLlmQueries();
      toast(next.enabled ? "答疑已启用，练习页「问一问」即时生效" : "答疑已停用，入口随之隐藏");
    },
    onError,
  });

  const createMutation = useMutation({
    mutationFn: createLlmProvider,
    onSuccess: (provider) => {
      invalidateLlmQueries();
      setForm(null);
      toast(`provider「${provider.name}」已新增，激活后成为答疑数据源`);
    },
    onError,
  });

  const updateMutation = useMutation({
    mutationFn: (input: {
      id: string;
      body: { name: string; protocol: LlmProtocol; baseUrl: string; model: string };
    }) => updateLlmProvider(input.id, input.body),
    onSuccess: (provider) => {
      invalidateLlmQueries();
      setForm(null);
      toast(`provider「${provider.name}」已更新`);
    },
    onError,
  });

  const activateMutation = useMutation({
    mutationFn: activateLlmProvider,
    onSuccess: (provider) => {
      invalidateLlmQueries();
      toast(`已激活「${provider.name}」，答疑数据源即时切换`);
    },
    onError,
  });

  const deleteMutation = useMutation({
    mutationFn: deleteLlmProvider,
    onSuccess: () => {
      invalidateLlmQueries();
      setDeleting(null);
      toast("provider 已从目录移除（环境变量密钥未改动）");
    },
    onError: (error: unknown) => {
      // 激活中不可删等业务文案原样透出；确认框保持打开便于改选其他激活条目。
      toast(error instanceof Error ? error.message : "操作失败，请稍后重试");
    },
  });

  if (configQuery.isPending || providersQuery.isPending) {
    return (
      <div>
        <p className="font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
          统一模型配置：密钥只经环境变量注入（provider 目录按 YILIAN_LLM_KEY_&lt;大写ID&gt; 索引），
          不入库、不回显；本页管理 provider 目录、激活切换与答疑开关。
        </p>
        <LoadingBlock />
      </div>
    );
  }
  if (configQuery.isError) {
    return (
      <ErrorState
        error={configQuery.error}
        onRetry={() => void configQuery.refetch()}
        variant="admin"
      />
    );
  }
  if (providersQuery.isError) {
    return (
      <ErrorState
        error={providersQuery.error}
        onRetry={() => void providersQuery.refetch()}
        variant="admin"
      />
    );
  }

  const config = configQuery.data;
  const providers = providersQuery.data;
  // 答疑可用 = 凭证按协议口径齐备且开关已开；两者缺一即「未开放」（练习页入口按 40304/40400 隐藏）。
  const askOpen = config.enabled && config.credentialsComplete;
  const activeProvider = providers.find((provider) => provider.active) ?? null;
  const busy =
    toggleMutation.isPending ||
    createMutation.isPending ||
    updateMutation.isPending ||
    activateMutation.isPending ||
    deleteMutation.isPending;
  const usageCalls = M3_USAGE.reduce((sum, item) => sum + item.calls, 0);
  const usageTokens = M3_USAGE.reduce((sum, item) => sum + item.tokens, 0);

  return (
    <div>
      <p className="font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
        统一模型配置：密钥只经环境变量注入（provider 目录按 YILIAN_LLM_KEY_&lt;大写ID&gt; 索引），
        不入库、不回显；本页管理 provider 目录、激活切换与答疑开关。
      </p>

      <div
        className={
          askOpen
            ? "mt-4 rounded-[2px] border border-moss/40 p-[18px]"
            : "mt-4 rounded-[2px] border border-rule p-[18px]"
        }
      >
        <div className="flex flex-wrap items-center justify-between gap-2.5">
          <span className="flex items-baseline gap-2 text-[13px] text-ink">
            答疑 LLM
            {config.protocol ? (
              <StatePill tone="muted">{config.protocol}</StatePill>
            ) : (
              <span className="font-mono text-[10px] text-ink-faint">未配置</span>
            )}
          </span>
          <LiveMark on={askOpen}>{askOpen ? "运行中" : "未开放"}</LiveMark>
        </div>

        <div className="mt-[18px] flex flex-wrap gap-x-12 gap-y-5">
          <div>
            <p className={configCellLabelClassName()}>凭证状态</p>
            <StatePill tone={config.credentialsComplete ? "ok" : "warn"}>
              {config.credentialsComplete ? "已配置" : "未配置"}
            </StatePill>
          </div>
          <div>
            <p className={configCellLabelClassName()}>当前模型</p>
            <p className="font-mono text-[12px] text-ink">{config.model || "—"}</p>
          </div>
          <div>
            <p className={configCellLabelClassName()}>生效端点</p>
            <p className="font-mono text-[12px] text-ink">{config.baseUrl || "—"}</p>
          </div>
          <div>
            <p className={configCellLabelClassName()}>答疑关联</p>
            <StatePill tone={askOpen ? "ok" : "muted"}>
              {askLinkageLabel(config.enabled, config.credentialsComplete)}
            </StatePill>
          </div>
        </div>

        {!config.credentialsComplete && config.providerId && config.providerId !== "legacy" ? (
          <div className="mt-[18px] rounded-[2px] border border-vermilion/30 bg-vermilion/5 p-3.5">
            <p className="text-[13px] text-ink">
              激活的 provider「{config.providerId}」凭证不齐：设置对应环境变量后重启服务端。
            </p>
            <p className="mt-2.5 font-mono text-[11px] leading-[1.9] text-ink-muted">
              {config.protocol === "anthropic-messages"
                ? `YILIAN_LLM_KEY_${config.providerId.replaceAll("-", "_").toUpperCase()}（anthropic-messages 轨可缺省，端点必填）`
                : `YILIAN_LLM_KEY_${config.providerId.replaceAll("-", "_").toUpperCase()}（模型服务密钥）`}
            </p>
            <p className="mt-2.5 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
              API Key 只经环境变量注入，不入库、不进配置文件、不回显；目录元数据存 app_settings。
            </p>
          </div>
        ) : null}

        {!config.credentialsComplete && (!config.providerId || config.providerId === "legacy") ? (
          <div className="mt-[18px] rounded-[2px] border border-vermilion/30 bg-vermilion/5 p-3.5">
            <p className="text-[13px] text-ink">
              尚未配置模型凭证，答疑与后续 AI 功能均不可用。在部署环境设置以下两个环境变量后重启服
              务端：
            </p>
            <p className="mt-2.5 font-mono text-[11px] leading-[1.9] text-ink-muted">
              YILIAN_LLM_BASE_URL（OpenAI 兼容端点，如 https://open.bigmodel.cn/api/paas/v4）
              <br />
              YILIAN_LLM_API_KEY（模型服务密钥）
            </p>
            <p className="mt-2.5 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
              API Key 只经环境变量注入，不入库、不进配置文件、不回显；后台开关只存 enabled。
            </p>
          </div>
        ) : null}

        <div className="mt-[18px] flex flex-wrap items-center gap-4">
          <button
            type="button"
            className="btn ghost"
            disabled={toggleMutation.isPending}
            onClick={() => toggleMutation.mutate({ enabled: !config.enabled })}
          >
            {config.enabled ? "停用答疑" : "启用答疑"}
          </button>
          <span className="font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
            开关存 app_settings（llm.tutor），保存即时生效，无需重启；改动写入操作日志。
          </span>
        </div>
        {config.credentialsComplete && !config.enabled ? (
          <p className="mt-3 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
            答疑未开放：练习页不出现「问一问」入口；开启后即时生效。
          </p>
        ) : null}
        {config.enabled && !config.credentialsComplete ? (
          // 对称说明：与上方「配了凭证没开」的提示互补，讲清这一组合
          // （开关已开、缺凭证）差什么、下一步做什么。
          <p className="mt-3 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
            开关已开启但未生效（缺凭证）：配置好 provider 环境变量并重启服务端后自动可用；
            练习页暂不出现「问一问」入口。
          </p>
        ) : null}
      </div>

      <AdminSection
        title="Provider 目录"
        action={
          <button
            type="button"
            className="btn ghost"
            aria-label="新增 provider"
            onClick={() => setForm({ mode: "create", state: formStateFor() })}
          >
            新增 provider
          </button>
        }
      >
        <p className="mb-3 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
          多协议多 provider 目录（CC Switch 式）：元数据存 app_settings，密钥按 id 走
          YILIAN_LLM_KEY_&lt;大写ID&gt; 环境变量；激活的 provider 是答疑等 AI
          功能的数据源，切换即时生效。
        </p>
        {providers.length === 0 ? (
          // legacy 单 provider 形态：目录为空时沿用环境变量直配，只读展示生效值。
          <div className="rounded-[2px] border border-rule p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-[13px] text-ink">环境变量直配（legacy 单 provider）</span>
              {config.providerId ? (
                <StatePill tone="muted">{config.protocol ?? "—"}</StatePill>
              ) : null}
            </div>
            <p className="mt-2 font-mono text-[11px] leading-[1.8] text-ink-muted">
              {config.baseUrl || "未配置 YILIAN_LLM_BASE_URL"}
              <span className="mx-2 text-ink-faint">·</span>
              {config.model || "—"}
            </p>
            <p className="mt-2.5 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
              当前模型接入来自 YILIAN_LLM_* 环境变量；新增 provider
              并激活后由目录接管，无需改动本卡。
            </p>
          </div>
        ) : (
          <ul className="m-0 space-y-3 p-0">
            {providers.map((provider) => (
              <ProviderCard
                key={provider.id}
                provider={provider}
                busy={busy}
                onActivate={(target) => activateMutation.mutate(target.id)}
                onEdit={(target) => setForm({ mode: "edit", state: formStateFor(target) })}
                onDelete={(target) => setDeleting(target)}
              />
            ))}
          </ul>
        )}
      </AdminSection>

      <AdminSection title="AI 功能清单">
        <RowList>
          <Row>
            <RowText
              main="题目答疑"
              meta={
                activeProvider
                  ? `练习页批注栏「问一问」；数据源：${activeProvider.name}（${activeProvider.protocol}）`
                  : "练习页批注栏「问一问」；未开放时入口自动隐藏（40304/40400）"
              }
            />
            <StatePill tone={askOpen ? "ok" : "muted"}>
              {askLinkageLabel(config.enabled, config.credentialsComplete)}
            </StatePill>
          </Row>
          {M3_FEATURES.map((feature) => (
            <Row key={feature.id}>
              <RowText main={feature.name} meta={feature.meta} />
              <StatePill tone="muted">M3 预留</StatePill>
            </Row>
          ))}
        </RowList>
      </AdminSection>

      <AdminSection title="任务路由" action={<StatePill tone="muted">M3 预留</StatePill>}>
        <RowList>
          {M3_ROUTES.map((route) => (
            <Row key={route.task}>
              <RowText main={route.task} meta={route.note} />
              <StatePill tone={route.model === "local-rules" ? "ok" : "muted"}>
                {route.model === "local-rules" ? "本地" : "模型"}
              </StatePill>
              <span className="truncate text-right font-mono text-[11px] text-ink-faint">
                {route.model}
              </span>
            </Row>
          ))}
        </RowList>
        <p className="mt-3.5 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
          各任务走本地规则还是模型、走哪个模型，随 M3 任务路由域落地；当前批改一律本地判定。
        </p>
      </AdminSection>

      <AdminSection title="Prompt 模板" action={<StatePill tone="muted">M3 预留</StatePill>}>
        <RowList>
          {M3_PROMPTS.map((prompt) => (
            <Row key={prompt.id}>
              <RowText main={prompt.name} meta={`v${prompt.version} · 更新于 ${prompt.updated}`} />
              <StatePill tone={prompt.enabled ? "ok" : "muted"}>
                {prompt.enabled ? "启用" : "停用"}
              </StatePill>
              <span className="truncate text-right font-mono text-[11px] text-ink-faint">
                随 M3 prompt_versions 落地
              </span>
            </Row>
          ))}
        </RowList>
        <p className="mt-3.5 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
          变量在调用时注入；保存即新版本，可回滚。用户答案一律当数据，不当指令。
        </p>
      </AdminSection>

      <AdminSection title="本月用量" action={<StatePill tone="muted">M3 预留</StatePill>}>
        <MetricRow
          items={[
            { value: usageCalls, label: "近 14 天请求（示例）" },
            { value: `${Math.round(usageTokens / 1000)}k`, label: "Tokens（示例）" },
            { value: "—", label: "估算成本" },
            { value: "—", label: "账户余额" },
          ]}
        />
        <div className="mt-6">
          <Bars
            values={M3_USAGE.map((item) => item.calls)}
            titles={M3_USAGE.map((item) => `${item.calls} 次 / ${item.tokens} tokens`)}
          />
          <BarsCaption left="14 天前" right="今天" />
        </div>
        <p className="mt-3.5 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
          用量观测随 M3 llm_usage 落库；当前 token/耗时记结构化日志，上图与数字为原型示例。
        </p>
      </AdminSection>

      <AdminSection title="预算" action={<StatePill tone="muted">M3 预留</StatePill>}>
        <p className="text-[13px] leading-[1.9] text-ink-muted">
          月度成本上限、到 80% 提醒、到 100% 自动降级为本地批改，随 M3 用量观测一并落地；当前答疑
          走既有模型套餐计费，不会静默扣费。
        </p>
      </AdminSection>

      {/* 新增/编辑抽屉：id 仅创建时可填（服务端路径 id 不可改）。 */}
      <Drawer
        open={form !== null}
        onClose={() => setForm(null)}
        kicker="Provider 目录"
        title={form?.mode === "edit" ? "编辑 provider" : "新增 provider"}
        meta="只存元数据；密钥按 id 走 YILIAN_LLM_KEY_<大写ID> 环境变量，不入库、不回显。"
      >
        {form ? (
          <form
            className="mt-6"
            onSubmit={(event) => {
              event.preventDefault();
              const state = form.state;
              if (
                !state.id.trim() ||
                !state.name.trim() ||
                !state.baseUrl.trim() ||
                !state.model.trim()
              ) {
                toast("id、名称、端点与模型名均为必填");
                return;
              }
              if (!/^[a-z0-9-]+$/.test(state.id.trim())) {
                toast("id 只允许小写字母、数字与连字符");
                return;
              }
              if (form.mode === "create") {
                createMutation.mutate({
                  id: state.id.trim(),
                  name: state.name.trim(),
                  protocol: state.protocol,
                  baseUrl: state.baseUrl.trim(),
                  model: state.model.trim(),
                });
              } else {
                updateMutation.mutate({
                  id: state.id.trim(),
                  body: {
                    name: state.name.trim(),
                    protocol: state.protocol,
                    baseUrl: state.baseUrl.trim(),
                    model: state.model.trim(),
                  },
                });
              }
            }}
          >
            <AdminField
              label="ID"
              hint={
                form.mode === "edit"
                  ? "id 创建后不可改"
                  : "小写字母/数字/连字符；env 变量名为 YILIAN_LLM_KEY_<大写ID>（连字符转下划线）"
              }
            >
              <input
                aria-label="ID"
                className="w-full border-b border-rule bg-transparent py-2 font-mono text-[12px] text-ink outline-none transition focus:border-vermilion"
                value={form.state.id}
                disabled={form.mode === "edit"}
                onChange={(event) =>
                  setForm({ ...form, state: { ...form.state, id: event.target.value } })
                }
              />
            </AdminField>
            <AdminField label="名称">
              <input
                aria-label="名称"
                className="w-full border-b border-rule bg-transparent py-2 text-[13px] text-ink outline-none transition focus:border-vermilion"
                value={form.state.name}
                onChange={(event) =>
                  setForm({ ...form, state: { ...form.state, name: event.target.value } })
                }
              />
            </AdminField>
            <AdminField label="协议">
              <select
                aria-label="协议"
                className="rounded-sm border border-rule bg-transparent px-2.5 py-[7px] text-xs text-ink-muted outline-none transition focus:border-ink-faint"
                value={form.state.protocol}
                onChange={(event) =>
                  setForm({
                    ...form,
                    state: { ...form.state, protocol: event.target.value as LlmProtocol },
                  })
                }
              >
                {(Object.keys(PROTOCOL_LABELS) as LlmProtocol[]).map((protocol) => (
                  <option key={protocol} value={protocol}>
                    {PROTOCOL_LABELS[protocol]}（{protocol}）
                  </option>
                ))}
              </select>
            </AdminField>
            <AdminField
              label="端点 Base URL"
              hint="anthropic-messages 填到网关前缀（/v1/messages 由客户端拼）；openai-chat 填到 /v4 级前缀；openai-responses 填到 /v1 之前的前缀。"
            >
              <input
                aria-label="端点 Base URL"
                className="w-full border-b border-rule bg-transparent py-2 font-mono text-[12px] text-ink outline-none transition focus:border-vermilion"
                value={form.state.baseUrl}
                onChange={(event) =>
                  setForm({ ...form, state: { ...form.state, baseUrl: event.target.value } })
                }
              />
            </AdminField>
            <AdminField label="模型名">
              <input
                aria-label="模型名"
                className="w-full border-b border-rule bg-transparent py-2 font-mono text-[12px] text-ink outline-none transition focus:border-vermilion"
                value={form.state.model}
                onChange={(event) =>
                  setForm({ ...form, state: { ...form.state, model: event.target.value } })
                }
              />
            </AdminField>
            {form.mode === "create" ? (
              <button
                type="button"
                className="linkish text-[12px]"
                onClick={() => setForm({ mode: "create", state: { ...ZCODE_PRESET } })}
              >
                填入 ZCode 网关模板
              </button>
            ) : null}
            <div className="mt-8 flex items-center gap-4">
              <button type="submit" className="btn primary" disabled={busy}>
                保存
              </button>
              <button type="button" className="btn ghost" onClick={() => setForm(null)}>
                取消
              </button>
            </div>
          </form>
        ) : null}
      </Drawer>

      <ConfirmDialog
        open={deleting !== null}
        title="删除 provider"
        description={
          deleting
            ? `从目录移除「${deleting.name}」（${deleting.id}）？只删目录元数据，环境变量密钥不改动；激活中的 provider 不能删除。`
            : ""
        }
        confirmLabel="删除"
        onConfirm={() => {
          if (deleting) {
            deleteMutation.mutate(deleting.id);
          }
        }}
        onCancel={() => setDeleting(null)}
      />

      {toastNode}
    </div>
  );
}
