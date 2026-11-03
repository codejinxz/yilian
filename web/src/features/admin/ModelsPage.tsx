import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { getLlmConfig, updateLlmConfig } from "@/lib/api/admin";
import { queryKeys } from "@/lib/query";
import {
  AdminSection,
  Bars,
  BarsCaption,
  LiveMark,
  MetricRow,
  Row,
  RowList,
  RowText,
  StatePill,
  useToastMessage,
} from "./ui";

/**
 * 模型页签（019 统一模型配置状态中心）。
 *
 * <p>架构裁决（019 计划）：模型凭据经 {@code YILIAN_LLM_*} 环境变量一次配置、全部 AI
 * 功能共享（AGENTS §3.4 密钥不入库，密钥不做 UI）；本页上半区为真实态——读
 * {@code GET /api/admin/llm/config} 回显 hasKey/enabled/model，启停走 PUT 并失效缓存；
 * 下半区（任务路由/Prompt 模板/本月用量/预算）是 M3 模型配置域的原型占位，只读展示。
 * mock 裁决：src/mocks 无 /admin/models 路径，本页直连真实端点，mock 层不新增演示路径。
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

/** 统一配置卡里一个「标签 + 值」小格的原型样式。 */
function configCellLabelClassName(): string {
  return "mb-[7px] font-mono text-[9px] tracking-[0.16em] text-ink-faint";
}

/**
 * 模型页签：上半区「统一模型配置」状态卡与「AI 功能清单」（真实态，019），
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

  // 启停只受理 enabled；成功失效缓存重新拉取（服务端是开关唯一事实源），失败透出业务文案。
  const toggleMutation = useMutation({
    mutationFn: updateLlmConfig,
    onSuccess: (next) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.llmConfig });
      toast(next.enabled ? "答疑已启用，练习页「问一问」即时生效" : "答疑已停用，入口随之隐藏");
    },
    onError: (error) => {
      toast(error instanceof Error ? error.message : "操作失败，请稍后重试");
    },
  });

  if (configQuery.isPending) {
    return (
      <div>
        <p className="font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
          统一模型配置：模型凭据经 YILIAN_LLM_* 环境变量一次配置，全部 AI 功能共享；本页管理答疑
          开关与功能状态。API Key 只经环境变量注入，不入库、不回显。
        </p>
        <LoadingBlock />
      </div>
    );
  }
  if (configQuery.isError) {
    return <ErrorState error={configQuery.error} onRetry={() => void configQuery.refetch()} />;
  }

  const config = configQuery.data;
  // 答疑可用 = 凭证齐备且开关已开；两者缺一即「未开放」（练习页入口按 40304/40400 隐藏）。
  const askOpen = config.enabled && config.hasKey;
  const usageCalls = M3_USAGE.reduce((sum, item) => sum + item.calls, 0);
  const usageTokens = M3_USAGE.reduce((sum, item) => sum + item.tokens, 0);

  return (
    <div>
      <p className="font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
        统一模型配置：模型凭据经 YILIAN_LLM_* 环境变量一次配置，全部 AI 功能共享；本页管理答疑开关
        与功能状态。API Key 只经环境变量注入，不入库、不回显。
      </p>

      <div
        className={
          askOpen
            ? "mt-4 rounded-[2px] border border-moss/40 p-[18px]"
            : "mt-4 rounded-[2px] border border-rule p-[18px]"
        }
      >
        <div className="flex flex-wrap items-center justify-between gap-2.5">
          <span className="text-[13px] text-ink">答疑 LLM（OpenAI 兼容）</span>
          <LiveMark on={askOpen}>{askOpen ? "运行中" : "未开放"}</LiveMark>
        </div>

        <div className="mt-[18px] flex flex-wrap gap-x-12 gap-y-5">
          <div>
            <p className={configCellLabelClassName()}>凭证状态</p>
            <StatePill tone={config.hasKey ? "ok" : "warn"}>
              {config.hasKey ? "已配置" : "未配置"}
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
            <StatePill tone={askOpen ? "ok" : "muted"}>{askOpen ? "启用中" : "未开放"}</StatePill>
          </div>
        </div>

        {!config.hasKey ? (
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
        {config.hasKey && !config.enabled ? (
          <p className="mt-3 font-mono text-[10px] leading-[1.7] tracking-[0.04em] text-ink-faint">
            答疑未开放：练习页不出现「问一问」入口；开启后即时生效。
          </p>
        ) : null}
      </div>

      <AdminSection title="AI 功能清单">
        <RowList>
          <Row>
            <RowText
              main="题目答疑"
              meta="练习页批注栏「问一问」；未开放时入口自动隐藏（40304/40400）"
            />
            <StatePill tone={askOpen ? "ok" : "muted"}>{askOpen ? "启用中" : "未开放"}</StatePill>
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

      {toastNode}
    </div>
  );
}
