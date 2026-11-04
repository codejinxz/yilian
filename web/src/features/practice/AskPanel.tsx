import { useMutation } from "@tanstack/react-query";
import { type FormEvent, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { askTutor } from "@/lib/api/practice";
import { ApiError } from "@/lib/api-error";
import { cn } from "@/lib/utils";

/** 与服务端一致的输入上限（API §6.12：question 1-500 字符）。 */
const QUESTION_MAX = 500;

/**
 * 三态门禁「不可用」两态的定向文案（017R2 DEF-01）。服务端对这两态只回短文案
 * （40304=「答疑未开放」、40400=通用「请求的资源不存在」），用户无从下手；前端按
 * API §6.12 的三态语义展开为可执行的提示，措辞对齐 API.md §8.14 的变量名与管理端入口。
 */
const UNAVAILABLE_MESSAGES: Record<number, string> = {
  40304: "答疑未开放：请先在管理端启用模型",
  40400: "答疑功能未配置：需先设置 YILIAN_LLM_* 环境变量",
};

/**
 * 收起前的停留时长（017R2 DEF-01）：让用户来得及读到原因、看到自己的问题还在，
 * 再触发 {@link AskPanelProps.onUnavailable} 收起；约 1.5s 够扫一眼又不至于像卡死。
 */
const UNAVAILABLE_DELAY_MS = 1500;

/** 会话内的一条消息；历史只存组件 state，不持久化、不落库（017 裁决）。 */
interface AskMessage {
  /** 会话内自增 id，作为列表 key（消息无业务主键，不能用数组下标）。 */
  id: number;
  role: "user" | "tutor";
  text: string;
  /** 答疑消息的免责标注；问句无。 */
  disclaimer?: string;
}

/**
 * 题目答疑面板（017，PRD §5.4）：被动一问一答，非 AI 外教。
 *
 * <p>三态显示策略：入口默认可见（乐观渲染），收到 40304「答疑未开放」或 40400「功能未配置」时
 * 先在面板内展示定向文案、问句与输入原样保留（017R2 DEF-01：此前直接收起，零提示且问题丢失），
 * 停留约 {@link UNAVAILABLE_DELAY_MS} 后再经 {@link AskPanelProps.onUnavailable} 通知父级整体
 * 隐藏——不加探针端点，代价是未开放实例上首次提问才会收起入口（已与计划确认）。
 * 会话历史保留在组件 state（换句/离开即清空），免责行常驻。
 *
 * @param props.sentenceId 当前句 id
 * @param props.onClose 收起面板
 * @param props.onUnavailable 服务端判定答疑不可用（40304/40400），父级应隐藏入口
 */
export function AskPanel({
  sentenceId,
  onClose,
  onUnavailable,
}: {
  sentenceId: string;
  onClose: () => void;
  onUnavailable: () => void;
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
      if (error instanceof ApiError && (error.code === 40304 || error.code === 40400)) {
        // 未开放/未配置（017R2 DEF-01）：先讲清原因、问句与输入原样保留（submit 失败不清
        // input，问句气泡已在 messages 里），停留约 1.5s 再通知父级收起——此前直接
        // onUnavailable() 静默卸载，用户问题跟着消失、重进页面再问再消失，循环困惑。
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
      setErrorText(error instanceof Error ? error.message : "答疑暂时不可用，请稍后再试");
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
                <span className="font-serif text-[13px]">{message.text}</span>
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
        // 017R2 OBS-2：写明最长等待预期——网关 5xx 有 30s 退避重试，超出本地判分的即时反馈
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
