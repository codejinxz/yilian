import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { ConfirmDialog } from "@/features/settings/confirm-dialog";
import {
  activateLlmProvider,
  createLlmProvider,
  deleteLlmProvider,
  getLlmConfig,
  getLlmProviders,
  type LlmConfig,
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

/** 模型页签（provider 目录管理）。 */

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

/** 页面顶部的现状说明：只描述本页与密钥的当前解析方式，不对密钥通道做唯一性承诺。 */
const PAGE_NOTE =
  "本页管理 provider 目录、激活切换与答疑开关；条目密钥可在抽屉录入（加密入库，" +
  "不回显）或按条目 ID 走环境变量；目录为空时可在「答疑 LLM」卡直接编辑端点/模型/密钥（保存即时生效），" +
  "开关与配置改动写入操作日志。";

/** 新增/编辑表单的字段状态；protocol 允许空串表达「未选择」，新增时不预填默认协议。 */
interface ProviderFormState {
  id: string;
  name: string;
  protocol: LlmProtocol | "";
  baseUrl: string;
  model: string;
  /** 写-only 密钥输入：永远空白起步（已存密钥不回填），提交时非空白才发送。 */
  apiKey: string;
  /** 编辑态下已入库密钥的尾 4 位（仅展示辨识，不含明文）；新增态恒 null。 */
  existingLast4: string | null;
}

/**
 * 答疑 LLM 卡 legacy 态（目录空/未激活）的编辑表单：当前模型/生效端点 inline 输入 + 写-only 密钥。
 * 输入框默认回显生效值（覆盖 > env）；密钥永远空白起步（已存密钥不回填，写-only）。
 */
interface LegacyFormState {
  enabled: boolean;
  baseUrl: string;
  model: string;
  /** 写-only 密钥输入：提交时非空白才发送（trim 归一）；保存成功后清空。 */
  apiKey: string;
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

/** 表单初始值：新增为全空白（协议也不预选），编辑回填条目（id 固定不可改）。 */
function formStateFor(provider?: LlmProvider): ProviderFormState {
  return {
    id: provider?.id ?? "",
    name: provider?.name ?? "",
    protocol: provider?.protocol ?? "",
    baseUrl: provider?.baseUrl ?? "",
    model: provider?.model ?? "",
    apiKey: "",
    existingLast4: provider?.keyLast4 ?? null,
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
          {provider.hasKey && provider.keyLast4 ? (
            // 尾 4 位辨识号只在入库态显示（env 态无此值）；仅作辨识，不算明文泄露。
            <span className="font-mono text-[10px] text-ink-faint">
              尾号 ••••{provider.keyLast4}
            </span>
          ) : null}
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
  // legacy 卡编辑表单：null=待惰性初始化（config 到位后以生效值回填）；dirty=true 表示用户有未保存输入，
  // 期间服务端状态变化不回填（防后台 refetch 抹掉输入），保存/重置/开关成功后统一对齐并清 dirty。
  const [legacyForm, setLegacyForm] = useState<LegacyFormState | null>(null);
  const [legacyFormDirty, setLegacyFormDirty] = useState(false);
  // 「重置为环境默认」的确认弹窗（清覆盖 + 清 tutor 密钥，预披露裁决口径）。
  const [resetConfirmOpen, setResetConfirmOpen] = useState(false);

  // 非编辑态下表单与服务端保持一致：config 首次到位时以生效值回填；目录删除/激活等外部变更
  // 引起 config 更新时同样回填（编辑态不回填，见 dirty 守卫）。函数式更新 + 值相等短路——
  // legacyForm 故意不进依赖：每次 set 一个新对象会让本 effect 无限自触发（React 渲染死循环）。
  useEffect(() => {
    const data = configQuery.data;
    if (!data) {
      return;
    }
    setLegacyForm((prev) => {
      if (prev !== null && legacyFormDirty) {
        return prev;
      }
      const next = {
        enabled: data.enabled,
        baseUrl: data.baseUrl,
        model: data.model,
        apiKey: "",
      };
      if (
        prev !== null &&
        prev.enabled === next.enabled &&
        prev.baseUrl === next.baseUrl &&
        prev.model === next.model &&
        prev.apiKey === ""
      ) {
        return prev;
      }
      return next;
    });
  }, [configQuery.data, legacyFormDirty]);

  /** 目录/配置双失效：激活与 CRUD 都影响 llm/config 的生效视图（服务端同一事实源）。 */
  function invalidateLlmQueries() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.llmProviders });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.llmConfig });
  }

  function onError(error: unknown) {
    toast(error instanceof Error ? error.message : "操作失败，请稍后重试");
  }

  /**
   * 配置写成功的统一收口：先把服务端回执落缓存（乐观刷新——徽标/回显立即换算），
   * 再失效缓存后台校准，并解除编辑态（表单下次渲染按服务端值对齐）。
   * @param next 服务端回执的配置视图
   * @param message 成功提示文案
   */
  function onConfigSaved(next: LlmConfig, message: string) {
    queryClient.setQueryData(queryKeys.admin.llmConfig, next);
    setLegacyFormDirty(false);
    invalidateLlmQueries();
    toast(message);
  }

  // 启停答疑：目录态只传 enabled（缺省覆盖字段=清除休眠覆盖，不伤及目录生效配置）；
  // legacy 态携带表单当前值一起提交（状态型字段语义下裸传 enabled 会清掉既有覆盖）。
  const toggleMutation = useMutation({
    mutationFn: updateLlmConfig,
    onSuccess: (next) => {
      onConfigSaved(
        next,
        next.enabled ? "答疑已启用，练习页「问一问」即时生效" : "答疑已停用，入口随之隐藏",
      );
    },
    onError,
  });

  // 保存 legacy 卡编辑（端点/模型覆盖 + 写-only 密钥）：服务端保存即时生效（覆盖轨换轨无需重启）。
  const saveConfigMutation = useMutation({
    mutationFn: updateLlmConfig,
    onSuccess: (next) => {
      onConfigSaved(
        next,
        next.source === "custom"
          ? "答疑配置已保存并即时生效"
          : "答疑配置已保存，端点与模型回退环境变量",
      );
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
      body: {
        name: string;
        protocol: LlmProtocol;
        baseUrl: string;
        model: string;
        apiKey?: string;
      };
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
      toast("provider 已从目录移除，其入库密钥一并删除（环境变量密钥未改动）");
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
          {PAGE_NOTE}
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
        {PAGE_NOTE}
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
            {/* 来源徽标（028）：目录激活=「目录」（字段在 provider 抽屉编辑）；覆盖态=「自定义」；纯 env=「环境变量」。 */}
            {activeProvider ? (
              <StatePill tone="muted">目录</StatePill>
            ) : config.source === "custom" ? (
              <StatePill tone="muted">自定义</StatePill>
            ) : (
              <StatePill tone="muted">环境变量</StatePill>
            )}
          </span>
          <LiveMark on={askOpen}>{askOpen ? "运行中" : "未开放"}</LiveMark>
        </div>

        {activeProvider ? (
          // 目录激活态（022/027A 语义）：字段只读展示，编辑走 provider 抽屉（预披露裁决，本批不改）。
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
        ) : (
          // legacy 态（028）：当前模型/生效端点 inline 可编辑（默认回显生效值），密钥写-only。
          <div className="mt-[18px] flex flex-wrap gap-x-12 gap-y-5">
            <div>
              <p className={configCellLabelClassName()}>凭证状态</p>
              <StatePill tone={config.credentialsComplete ? "ok" : "warn"}>
                {config.credentialsComplete ? "已配置" : "未配置"}
              </StatePill>
            </div>
            <div className="min-w-[220px] flex-1">
              <p className={configCellLabelClassName()}>当前模型</p>
              {legacyForm ? (
                <input
                  aria-label="当前模型"
                  className="w-full border-b border-rule bg-transparent py-2 font-mono text-[12px] text-ink outline-none transition focus:border-vermilion"
                  value={legacyForm.model}
                  onChange={(event) => {
                    setLegacyForm({ ...legacyForm, model: event.target.value });
                    setLegacyFormDirty(true);
                  }}
                />
              ) : (
                <p className="font-mono text-[12px] text-ink">—</p>
              )}
            </div>
            <div className="min-w-[280px] flex-1">
              <p className={configCellLabelClassName()}>生效端点</p>
              {legacyForm ? (
                <input
                  aria-label="生效端点"
                  className="w-full border-b border-rule bg-transparent py-2 font-mono text-[12px] text-ink outline-none transition focus:border-vermilion"
                  value={legacyForm.baseUrl}
                  onChange={(event) => {
                    setLegacyForm({ ...legacyForm, baseUrl: event.target.value });
                    setLegacyFormDirty(true);
                  }}
                />
              ) : (
                <p className="font-mono text-[12px] text-ink">—</p>
              )}
            </div>
            <div>
              <p className={configCellLabelClassName()}>答疑关联</p>
              <StatePill tone={askOpen ? "ok" : "muted"}>
                {askLinkageLabel(config.enabled, config.credentialsComplete)}
              </StatePill>
            </div>
            <div className="min-w-[240px] flex-1">
              <p className={configCellLabelClassName()}>答疑 API 密钥</p>
              {legacyForm ? (
                <input
                  aria-label="答疑 API 密钥"
                  type="password"
                  autoComplete="new-password"
                  className="w-full border-b border-rule bg-transparent py-2 font-mono text-[12px] text-ink outline-none transition focus:border-vermilion"
                  value={legacyForm.apiKey}
                  onChange={(event) => {
                    setLegacyForm({ ...legacyForm, apiKey: event.target.value });
                    setLegacyFormDirty(true);
                  }}
                />
              ) : (
                <p className="font-mono text-[12px] text-ink">—</p>
              )}
              <p className="mt-1 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
                {config.keyLast4
                  ? `已存密钥尾号 ••••${config.keyLast4}（不回显）；留空=不变更，填入=加密覆盖。`
                  : config.hasKey
                    ? "密钥经环境变量注入（不回显）；填入=加密入库覆盖。"
                    : "可选：填入=加密入库（不回显）；留空则走环境变量 YILIAN_LLM_API_KEY。"}
              </p>
            </div>
          </div>
        )}

        {!config.credentialsComplete && config.providerId && config.providerId !== "legacy" ? (
          <div className="mt-[18px] rounded-[2px] border border-vermilion/30 bg-vermilion/5 p-3.5">
            <p className="text-[13px] text-ink">
              激活的 provider「{config.providerId}」凭证不齐：编辑该条目录入 API
              密钥（加密入库、即时生效无需重启），或设置对应环境变量。
            </p>
            <p className="mt-2.5 font-mono text-[11px] leading-[1.9] text-ink-muted">
              {config.protocol === "anthropic-messages"
                ? `YILIAN_LLM_KEY_${config.providerId.replaceAll("-", "_").toUpperCase()}（anthropic-messages 轨可缺省，端点必填）`
                : `YILIAN_LLM_KEY_${config.providerId.replaceAll("-", "_").toUpperCase()}（模型服务密钥）`}
            </p>
            <p className="mt-2.5 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
              API Key 经加密入库或环境变量注入，明文不回显、不进日志。
            </p>
          </div>
        ) : null}

        {!config.credentialsComplete && (!config.providerId || config.providerId === "legacy") ? (
          <div className="mt-[18px] rounded-[2px] border border-vermilion/30 bg-vermilion/5 p-3.5">
            <p className="text-[13px] text-ink">
              尚未配置模型凭证，答疑与后续 AI 功能均不可用。可直接在上方填写端点并录入 API
              密钥（保存即时生效、加密入库），或在部署环境设置以下两个环境变量：
            </p>
            <p className="mt-2.5 font-mono text-[11px] leading-[1.9] text-ink-muted">
              YILIAN_LLM_BASE_URL（OpenAI 兼容端点，如 https://open.bigmodel.cn/api/paas/v4）
              <br />
              YILIAN_LLM_API_KEY（模型服务密钥）
            </p>
            <p className="mt-2.5 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
              API Key 可加密入库或经环境变量注入，明文不回显、不进日志。
            </p>
          </div>
        ) : null}

        <div className="mt-[18px] flex flex-wrap items-center gap-4">
          <button
            type="button"
            className="btn ghost"
            disabled={toggleMutation.isPending || (!activeProvider && !legacyForm)}
            onClick={() => {
              if (activeProvider) {
                // 目录态：开关独立提交（缺省覆盖字段=清除休眠覆盖，不伤及目录生效配置）。
                toggleMutation.mutate({ enabled: !config.enabled });
                return;
              }
              // legacy 态（028）：开关与表单当前值一起提交——状态型字段语义下裸传 enabled 会清掉既有覆盖。
              toggleMutation.mutate({
                enabled: !config.enabled,
                baseUrl: legacyForm?.baseUrl.trim() ?? "",
                model: legacyForm?.model.trim() ?? "",
              });
            }}
          >
            {config.enabled ? "停用答疑" : "启用答疑"}
          </button>
          {!activeProvider ? (
            <>
              <button
                type="button"
                className="btn primary"
                disabled={saveConfigMutation.isPending || !legacyForm}
                onClick={() => {
                  if (!legacyForm) {
                    return;
                  }
                  // 状态型字段语义：总是携带端点/模型当前值（值=落覆盖，空串=清除回 env）；密钥写-only 非空白才发送。
                  saveConfigMutation.mutate({
                    enabled: legacyForm.enabled,
                    baseUrl: legacyForm.baseUrl.trim(),
                    model: legacyForm.model.trim(),
                    apiKey: legacyForm.apiKey.trim() || undefined,
                  });
                }}
              >
                保存答疑配置
              </button>
              <button
                type="button"
                className="btn ghost"
                disabled={saveConfigMutation.isPending || !legacyForm}
                onClick={() => setResetConfirmOpen(true)}
              >
                重置为环境默认
              </button>
            </>
          ) : null}
          <span className="font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
            保存即时生效，无需重启；改动写入操作日志。
          </span>
        </div>
        {activeProvider ? (
          <p className="mt-3 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
            目录激活态：当前模型、生效端点与密钥在上方 provider 卡「编辑」抽屉中修改，保存即时生效。
          </p>
        ) : null}
        {config.credentialsComplete && !config.enabled ? (
          <p className="mt-3 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
            答疑未开放：练习页不出现「问一问」入口；开启后即时生效。
          </p>
        ) : null}
        {config.enabled && !config.credentialsComplete ? (
          // 对称说明：与上方「配了凭证没开」的提示互补，讲清这一组合
          // （开关已开、缺凭证）差什么、下一步做什么。
          <p className="mt-3 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
            {activeProvider
              ? "开关已开启但未生效（缺凭证）：在激活条目的抽屉里录入 API 密钥或设置对应环境变量后自动可用；练习页暂不出现「问一问」入口。"
              : "开关已开启但未生效（缺凭证）：在上方补齐端点与密钥（保存即时生效）后自动可用；练习页暂不出现「问一问」入口。"}
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
          多协议多 provider 目录：激活的 provider 是答疑等 AI
          功能的数据源，切换即时生效；条目密钥可在抽屉录入（加密入库、不回显）或按条目 ID
          走环境变量，删除条目时入库密钥一并删除。
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
              目录为空时的回退形态：模型接入在上方「答疑
              LLM」卡直接编辑（端点/模型/密钥，保存即时生效）， 或沿用 YILIAN_LLM_* 环境变量；新增
              provider 并激活后由目录接管。
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
        meta="元数据即时生效；API 密钥为写-only——填入=加密入库（不回显），留空=不变更。"
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
              // 协议不预填默认值：未显式选择就不提交，避免静默落成错误协议。
              if (!state.protocol) {
                toast("请选择协议");
                return;
              }
              // 写-only 密钥：非空白才随请求发送（trim 归一）；空/缺省=不变更。
              const apiKey = state.apiKey.trim() || undefined;
              if (form.mode === "create") {
                createMutation.mutate({
                  id: state.id.trim(),
                  name: state.name.trim(),
                  protocol: state.protocol,
                  baseUrl: state.baseUrl.trim(),
                  model: state.model.trim(),
                  apiKey,
                });
              } else {
                updateMutation.mutate({
                  id: state.id.trim(),
                  body: {
                    name: state.name.trim(),
                    protocol: state.protocol,
                    baseUrl: state.baseUrl.trim(),
                    model: state.model.trim(),
                    apiKey,
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
                    // 空串对应「请选择协议」占位项：允许改回未选择态，提交前再校验。
                    state: { ...form.state, protocol: event.target.value as LlmProtocol | "" },
                  })
                }
              >
                {/* 占位空选项：新增时协议不预填默认值，须显式选择后才可保存。 */}
                <option value="">请选择协议</option>
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
            <AdminField
              label="API 密钥"
              hint={
                form.mode === "edit"
                  ? form.state.existingLast4
                    ? `已存密钥尾号 ••••${form.state.existingLast4}（不回显）；留空=不变更，填入=加密覆盖。`
                    : "留空=不变更；填入=加密入库覆盖（env 密钥不受影响）。"
                  : "可选：填入=加密入库（不回显）；留空则按条目 ID 走环境变量 / legacy。"
              }
            >
              <input
                aria-label="API 密钥"
                type="password"
                autoComplete="new-password"
                className="w-full border-b border-rule bg-transparent py-2 font-mono text-[12px] text-ink outline-none transition focus:border-vermilion"
                value={form.state.apiKey}
                onChange={(event) =>
                  setForm({ ...form, state: { ...form.state, apiKey: event.target.value } })
                }
              />
            </AdminField>
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
            ? `从目录移除「${deleting.name}」（${deleting.id}）？其入库密钥一并删除（环境变量密钥不改动）；激活中的 provider 不能删除。`
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

      {/* 重置为环境默认（028 预披露裁决）：清端点/模型覆盖 + 删 tutor 密钥行，生效值回退环境变量；开关状态不变。 */}
      <ConfirmDialog
        open={resetConfirmOpen}
        title="重置为环境默认"
        description="清除端点与模型的覆盖值，并删除已录入的答疑 API 密钥（如仅走环境变量则无入库密钥可删）；生效值回退 YILIAN_LLM_* 环境变量，答疑开关保持不变。"
        confirmLabel="重置"
        onConfirm={() => {
          setResetConfirmOpen(false);
          if (!legacyForm) {
            return;
          }
          saveConfigMutation.mutate({
            enabled: legacyForm.enabled,
            baseUrl: "",
            model: "",
            clearKey: true,
          });
        }}
        onCancel={() => setResetConfirmOpen(false)}
      />

      {toastNode}
    </div>
  );
}
