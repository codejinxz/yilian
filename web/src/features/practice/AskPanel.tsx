import { useMutation } from "@tanstack/react-query";
import { type FormEvent, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { askTutor } from "@/lib/api/practice";
import { ApiError } from "@/lib/api-error";
import { cn } from "@/lib/utils";

/** 与服务端一致的输入上限（API §6.12：question 1-500 字符）。 */
const QUESTION_MAX = 500;

/**
 * 三态门禁「不可用」两态的定向文案。服务端对这两态只回短文案
 * （40304=「答疑未开放」、40400=通用「请求的资源不存在」），用户无从下手；前端按
 * API §6.12 的三态语义展开为可执行的提示，措辞对齐 API.md §8.14 的变量名与管理端入口。
 */
const UNAVAILABLE_MESSAGES: Record<number, string> = {
  40304: "答疑未开放：请先在管理端启用答疑",
  40400: "答疑功能未配置：需先设置 YILIAN_LLM_* 环境变量",
};

/**
 * 预算强制门（40306）的定向文案：预算用尽是月度状态（下月或调预算后自动恢复），
 * 与「未开放/未配置」不同——面板不收起、入口保留，用户重试即可随恢复自动可用。
 */
const BUDGET_EXHAUSTED_MESSAGE = "本月模型预算已用尽：请下月再试，或联系管理员调整预算";

/**
 * 收起前的停留时长：让用户来得及读到原因、看到自己的问题还在，
 * 再触发 {@link AskPanelProps.onUnavailable} 收起；约 1.5s 够扫一眼又不至于像卡死。
 */
const UNAVAILABLE_DELAY_MS = 1500;

/**
 * 答疑回答的 Markdown 容器样式：react-markdown + remark-gfm 渲染代码块/列表/
 * 粗体/表格，不传 rehype-raw——原始 HTML 一律按纯文本展示（默认安全面，无 XSS 注入口）。
 * 用子元素变体逐类给样式（稿纸语言：无圆角阶差、1px rule、等宽小字号代码），不引
 * @tailwindcss/typography 依赖。用户问句不走 Markdown（仍纯文本），避免问句里的
 * 特殊符号被吞。
 */
const MARKDOWN_BODY_CLASS = cn(
  "font-serif text-[13px] [&_a]:text-vermilion [&_a]:underline",
  "[&_blockquote]:border-l-2 [&_blockquote]:border-rule [&_blockquote]:pl-2.5 [&_blockquote]:text-ink-muted",
  "[&_code]:rounded-[2px] [&_code]:border [&_code]:border-rule [&_code]:bg-ink/5 [&_code]:px-1 [&_code]:py-px [&_code]:font-mono [&_code]:text-[11.5px]",
  "[&_h1]:mb-1 [&_h1]:mt-2 [&_h1]:font-serif [&_h1]:text-[14px] [&_h2]:mb-1 [&_h2]:mt-2 [&_h2]:font-serif [&_h2]:text-[13.5px] [&_h3]:mb-1 [&_h3]:mt-1.5 [&_h3]:text-[13px]",
  "[&_li]:my-0.5 [&_ol]:my-1 [&_ol]:list-decimal [&_ol]:pl-5 [&_p]:my-1 [&_p]:first:mt-0 [&_p]:last:mb-0",
  "[&_pre]:my-1.5 [&_pre]:overflow-x-auto [&_pre]:p-2 [&_pre_code]:border-0 [&_pre_code]:bg-transparent [&_pre_code]:p-0",
  "[&_strong]:font-semibold [&_table]:my-1.5 [&_table]:border-collapse [&_td]:border [&_td]:border-rule [&_td]:px-2 [&_td]:py-1 [&_th]:border [&_th]:border-rule [&_th]:px-2 [&_th]:py-1 [&_ul]:my-1 [&_ul]:list-disc [&_ul]:pl-5",
);

/** 会话内的一条消息；历史只存组件 state，不持久化、不落库。 */
interface AskMessage {
  /** 会话内自增 id，作为列表 key（消息无业务主键，不能用数组下标）。 */
  id: number;
  role: "user" | "tutor";
  text: string;
  /** 答疑消息的免责标注；问句无。 */
  disclaimer?: string;
}

/**
 * 题目答疑面板（PRD §5.4）：被动一问一答，非 AI 外教。
 *
 * <p>三态显示策略：入口默认可见（乐观渲染），收到 40304「答疑未开放」或 40400「功能未配置」时
 * 先在面板内展示定向文案、问句与输入原样保留（避免直接收起导致零提示且问题丢失），
 * 停留约 {@link UNAVAILABLE_DELAY_MS} 后再经 {@link AskPanelProps.onUnavailable} 通知父级整体
 * 隐藏——不加探针端点，代价是未开放实例上首次提问才会收起入口。
 * 会话历史保留在组件 state（换句/离开即清空），免责行常驻。
 *
 * @param props.sentenceId 当前句 id
 * @param props.onClose 收起面板
 * @param props.onUnavailable 服务端判定答疑不可用（40304/40400），父级应隐藏入口
 * @param props.onTransientUnavailable 暂时性失败（网络/5xx 等），父级在入口旁挂「暂不可用」角标
 */
export function AskPanel({
  sentenceId,
  onClose,
  onUnavailable,
  onTransientUnavailable,
}: {
  sentenceId: string;
  onClose: () => void;
  onUnavailable: () => void;
  onTransientUnavailable: () => void;
}) {
  const [messages, setMessages] = useState<AskMessage[]>([]);
  const [input, setInput] = useState("");
  const [errorText, setErrorText] = useState<string | null>(null);
  /** 消息 id 计数器（会话内自增）。 */
  const nextIdRef = useRef(1);
  /** 「不可用」收起计时器；卸载时清掉，避免面板消失后还触发 onUnavailable。 */
  const unavailableTimerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (unavailableTimerRef.current !== null) {
        window.clearTimeout(unavailableTimerRef.current);
      }
    };
  }, []);

  const askMutation = useMutation({
    mutationFn: (question: string) => askTutor(sentenceId, question),
    onSuccess: (reply) => {
      setErrorText(null);
      setInput("");
      setMessages((prev) => [
        ...prev,
        {
          id: nextIdRef.current++,
          role: "tutor",
          text: reply.answer,
          disclaimer: reply.disclaimer,
        },
      ]);
    },
    onError: (error: unknown) => {
      if (error instanceof ApiError && error.code === 40306) {
        // 预算强制门：展示定向文案、问句与输入原样保留，面板不收起（入口保留——
        // 预算是月度状态，恢复后无需重新发现入口）。
        setErrorText(BUDGET_EXHAUSTED_MESSAGE);
        return;
      }
      if (error instanceof ApiError && (error.code === 40304 || error.code === 40400)) {
        // 未开放/未配置：先讲清原因、问句与输入原样保留（submit 失败不清
        // input，问句气泡已在 messages 里）；停留约 1.5s 再收起，让用户读得见原因，避免静默卸载把刚输入的问题一并吞掉。
        setErrorText(UNAVAILABLE_MESSAGES[error.code] ?? "答疑暂不可用");
        if (unavailableTimerRef.current !== null) {
          window.clearTimeout(unavailableTimerRef.current);
        }
        unavailableTimerRef.current = window.setTimeout(() => {
          unavailableTimerRef.current = null;
          onUnavailable();
        }, UNAVAILABLE_DELAY_MS);
        return;
      }
      const message = error instanceof Error ? error.message : "答疑暂时不可用，请稍后再试";
      setErrorText(message);
      // 失败状态不能只留在已展开的面板里（面板一收起文案就随卸载消失）：
      // 通知父级在「问一问」入口旁挂角标，收起后不可用状态仍然可见。
      onTransientUnavailable();
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const question = input.trim();
    if (!question || askMutation.isPending) {
      return;
    }
    // 问句乐观上屏；失败保留在会话里便于修改重问。
    setMessages((prev) => [...prev, { id: nextIdRef.current++, role: "user", text: question }]);
    askMutation.mutate(question);
  }

  const canSubmit = input.trim().length > 0 && !askMutation.isPending;

  return (
    <section aria-label="题目答疑" className="text-[12.5px] leading-[1.8]">
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="font-mono text-[10px] tracking-[0.18em] text-ink-faint">问一问</h3>
        <button type="button" className="linkish text-[11px]" onClick={onClose}>
          收起
        </button>
      </div>

      {messages.length > 0 ? (
        <ol className="m-0 mb-3 list-none space-y-2 p-0">
          {messages.map((message) =>
            message.role === "user" ? (
              <li
                key={message.id}
                className="rounded-[2px] border border-rule bg-ink/5 px-2.5 py-1.5 text-ink-muted"
              >
                <b className="mr-1.5 font-mono text-[9px] font-medium uppercase tracking-[0.08em] text-ink-faint">
                  问
                </b>
                {message.text}
              </li>
            ) : (
              <li key={message.id} className="px-2.5 py-1.5 text-ink">
                <b className="mr-1.5 font-mono text-[9px] font-medium uppercase tracking-[0.08em] text-vermilion">
                  答
                </b>
                {/* 回答走 Markdown 渲染：默认不渲染原始 HTML；问句保持纯文本。 */}
                <div className={MARKDOWN_BODY_CLASS}>
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.text}</ReactMarkdown>
                </div>
                <em className="mt-0.5 block text-[10px] not-italic text-ink-faint">
                  {message.disclaimer}
                </em>
              </li>
            ),
          )}
        </ol>
      ) : (
        <p className="mb-3 text-xs text-ink-faint">
          针对当前句的中文、考点或批注提问，回答只作引导，不代批改。
        </p>
      )}

      {askMutation.isPending ? (
        // 写明最长等待预期——网关 5xx 有 30s 退避重试，超出本地判分的即时反馈
        // 节奏，不设预期会被误判为卡死而反复提交（5/min 限流后反而 42900）。
        <p role="status" className="mb-2 text-xs text-ink-faint">
          正在思考…（最长约 30 秒）
        </p>
      ) : null}
      {errorText ? (
        <p role="alert" className="mb-2 text-xs text-vermilion">
          {errorText}
        </p>
      ) : null}

      <form onSubmit={submit} className="flex flex-col gap-2">
        <Textarea
          value={input}
          maxLength={QUESTION_MAX}
          rows={2}
          placeholder="例如：这里为什么用 attach importance to？"
          onChange={(event) => setInput(event.target.value)}
          className={cn("text-[12.5px]")}
        />
        <div className="flex items-center justify-between">
          {/* 免责行常驻（AC-5）：不随回答出现/消失。 */}
          <em className="text-[10px] not-italic text-ink-faint">AI 生成，可能有误</em>
          <Button type="submit" size="sm" disabled={!canSubmit} aria-disabled={!canSubmit}>
            提问
          </Button>
        </div>
      </form>
    </section>
  );
}
