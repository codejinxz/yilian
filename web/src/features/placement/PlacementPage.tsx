import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { normalizeAnswer } from "@/features/practice/grading";
import {
  getPlacementSentences,
  savePlacementState,
  setDailyGoal as setEngineDailyGoal,
} from "@/lib/local-engine";
import type { PracticeSentence } from "@/lib/schemas/practice";
import { cn } from "@/lib/utils";

/** 定级题查询键：页面私有（queryKeys 工厂只登记跨页共享的业务查询）。 */
const PLACEMENT_SENTENCES_KEY = ["placement", "sentences"] as const;

/** linkish 文字按钮样式；与原型 .linkish 一致（12px 朱红文字 + 1px 短下划线）。 */
function linkishClass(): string {
  return "cursor-pointer border-b border-vermilion/35 bg-transparent text-[12px] text-vermilion";
}

/** 起点档位只描述「先从哪档开始」，绝不出现分数或证书类等级标签（契约 §2.8 红线）。 */
function startLevelFor(correctCount: number): string {
  return correctCount >= 2 ? "中级·日常" : "初级·日常";
}

/**
 * 三题定级页：渲染在壳层内（view-placement：完整顶栏 + view + 页脚）。
 * 题目来自本地引擎（游客可用）；判分用与练习页相同的本地归一化比对，纯前端完成；
 * 结果只落「先从哪档开始 + 先补哪个考点」，状态持久化全部走 local-engine，本页不碰 localStorage。
 */
export function PlacementPage() {
  const navigate = useNavigate();

  const [index, setIndex] = useState(0);
  const [answer, setAnswer] = useState("");
  const [hint, setHint] = useState<string | null>(null);
  const [correctCount, setCorrectCount] = useState(0);
  const [missedTags, setMissedTags] = useState<string[]>([]);
  const [result, setResult] = useState<{ correct: number; missedTags: string[] } | null>(null);
  const [dailyGoal, setDailyGoal] = useState<1 | 3 | 5>(3);

  // 定级题一次性拉取；空列表按失败处理，重试态交给 ErrorState。
  const sentencesQuery = useQuery<PracticeSentence[]>({
    queryKey: PLACEMENT_SENTENCES_KEY,
    queryFn: async () => {
      const list = await getPlacementSentences();
      if (!list || list.length === 0) {
        throw new Error("题库暂时没有可用的定级题");
      }
      return list;
    },
    retry: false,
  });

  const sentences = sentencesQuery.data ?? null;
  const loadState: "loading" | "error" | "ready" = sentencesQuery.isPending
    ? "loading"
    : sentencesQuery.isError
      ? "error"
      : "ready";
  const loadError =
    sentencesQuery.error instanceof Error ? sentencesQuery.error : new Error("定级题目拉取失败");

  /**
   * 进入下一题或收卷；答错与跳过都会记下该题首个考点，答对不记。
   * @param hit 本题是否命中参考译文
   */
  function advance(hit: boolean) {
    const current = sentences?.[index];
    if (!current || result) {
      return;
    }
    const tag = current.tags[0];
    const nextCorrect = correctCount + (hit ? 1 : 0);
    const nextMissed = !hit && tag ? [...missedTags, tag] : missedTags;
    setCorrectCount(nextCorrect);
    setMissedTags(nextMissed);
    if (sentences && index + 1 < sentences.length) {
      setIndex(index + 1);
      setAnswer("");
      setHint(null);
    } else {
      setResult({ correct: nextCorrect, missedTags: nextMissed });
    }
  }

  /**
   * 提交当前题：空输入只提示不打分；其余按归一化后的整句比对计分。
   * @param event 输入框回车或按钮触发的默认表单事件
   */
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const current = sentences?.[index];
    if (!current || result) {
      return;
    }
    if (normalizeAnswer(answer) === "") {
      setHint("先写一句，或者跳过");
      return;
    }
    setHint(null);
    advance(normalizeAnswer(answer) === normalizeAnswer(current.ref));
  }

  /** 「先开始，稍后再测」：直接视为已定级（不定起点），回今日。 */
  function skipPlacement() {
    savePlacementState({ placed: true, startLevel: null, missedTags: [] });
    void navigate({ to: "/today" });
  }

  /** 结果屏的「开始」：记录起点、错点与每日目标，回今日启动台。 */
  function startWithPlacement() {
    if (!result) {
      return;
    }
    // 目标与定级状态一起落本地：引擎的 today 快照按此目标计算「已练 n / 目标」。
    setEngineDailyGoal(dailyGoal);
    savePlacementState({
      placed: true,
      startLevel: startLevelFor(result.correct),
      missedTags: result.missedTags,
    });
    void navigate({ to: "/today" });
  }

  const current = sentences?.[index];
  const startLevel = result ? startLevelFor(result.correct) : null;

  return (
    // view-placement：壳层已提供顶栏与页脚，本页只画 560px 窄栏（今日页同款双层居中）。
    <div className="mx-auto w-full max-w-[560px] pt-[8vh]">
      {loadState === "loading" ? <LoadingBlock label="正在准备定级题…" rows={3} /> : null}

      {loadState === "error" ? (
        <div>
          <ErrorState
            title="定级题目拉取失败"
            error={loadError}
            onRetry={() => void sentencesQuery.refetch()}
          />
          <p className="mt-3 text-xs leading-5 text-ink-faint">
            定级题需要联网拉取题库；也可以稍后再测，先去今日随便练一句。
          </p>
          <button type="button" className={cn("mt-4", linkishClass())} onClick={skipPlacement}>
            先开始，稍后再测
          </button>
        </div>
      ) : null}

      {loadState === "ready" && current && !result ? (
        <form onSubmit={handleSubmit} noValidate>
          <p className="flex flex-wrap items-center gap-2.5 font-mono text-[11px] tracking-[0.12em] text-ink-faint">
            <span>定级</span>
            <span className="h-[3px] w-[3px] rounded-full bg-ink-faint" aria-hidden="true" />
            <span>
              {index + 1} / {sentences?.length ?? 3}
            </span>
          </p>

          <h1 className="mb-[44px] mt-[22px] font-serif font-medium text-[clamp(24px,3.4vw,34px)] leading-[1.55] tracking-[0.02em] text-ink">
            {current.zh}
          </h1>

          {/* .freeinput（min-height:auto）：20px/1.9 衬线、无框底线、4px 0 内距、朱红聚焦 */}
          <input
            className="block w-full border-b border-rule bg-transparent py-[4px] font-serif text-xl leading-[1.9] text-ink outline-none transition-colors placeholder:italic placeholder:text-ink-faint focus:border-vermilion"
            value={answer}
            onChange={(event) => {
              setAnswer(event.target.value);
              if (hint) {
                setHint(null);
              }
            }}
            onKeyDown={(event) => {
              // 中文输入法组词中的回车不是提交意图。
              if (event.nativeEvent.isComposing) {
                return;
              }
              if (event.key === "Enter") {
                event.preventDefault();
                event.currentTarget.form?.requestSubmit();
              }
            }}
            placeholder="写下英文译文…"
            aria-label="英文译文"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            // biome-ignore lint/a11y/noAutofocus: 定级是单输入整屏，进入即写是的刻意交互
            autoFocus
          />

          {hint ? (
            <p role="status" className="mt-3 text-xs leading-5 text-vermilion-deep">
              {hint}
            </p>
          ) : null}

          {/* .actions：36px 上距、14px 间距；提交为 .btn.primary（13px、11px 22px、2px 圆角） */}
          <div className="mt-9 flex flex-wrap items-center gap-3.5">
            <button
              type="submit"
              className="cursor-pointer rounded-[2px] border border-vermilion bg-vermilion px-[22px] py-[11px] text-[13px] leading-[1.2] tracking-[0.06em] text-white transition-colors duration-200 hover:border-vermilion-deep hover:bg-vermilion-deep"
            >
              提交
            </button>
            <button type="button" className={linkishClass()} onClick={() => advance(false)}>
              跳过这题
            </button>
            <button type="button" className={linkishClass()} onClick={skipPlacement}>
              先开始，稍后再测
            </button>
          </div>
        </form>
      ) : null}

      {loadState === "ready" && result ? (
        <div>
          <p className="flex flex-wrap items-center gap-2.5 font-mono text-[11px] tracking-[0.12em] text-ink-faint">
            <span>定级</span>
            <span className="h-[3px] w-[3px] rounded-full bg-ink-faint" aria-hidden="true" />
            <span>完成</span>
          </p>

          {/* 结果大标题沿用 .prompt 的字号行高（原型把结果写进 plZh 的 h1.prompt） */}
          <h1 className="mb-[44px] mt-[22px] font-serif font-medium text-[clamp(24px,3.4vw,34px)] leading-[1.55] tracking-[0.02em] text-ink">
            先从「{startLevel}」开始
          </h1>

          <div>
            <p className="mb-4 font-mono text-[10px] tracking-[0.18em] text-ink-faint">起点</p>
            <p className="mt-2.5 font-serif text-[19px] leading-[1.75] text-ink">
              不贴等级标签，10 题后自动校准。
              {result.missedTags[0] ? <>先补「{result.missedTags[0]}」。</> : null}
            </p>
          </div>

          {/* .field + .select：label 9px mono、透明底 1px rule 边 2px 圆角、focus 边框变 ink3 */}
          <div className="mb-5">
            <label
              htmlFor="placement-goal"
              className="mb-[9px] block font-mono text-[9px] tracking-[0.18em] text-ink-faint"
            >
              每天几题
            </label>
            <select
              id="placement-goal"
              value={dailyGoal}
              onChange={(event) => setDailyGoal(Number(event.target.value) as 1 | 3 | 5)}
              className="cursor-pointer rounded-[2px] border border-rule bg-transparent px-[10px] py-[7px] text-xs text-ink-muted outline-none transition-colors focus:border-ink-faint"
            >
              <option value={1}>1 题</option>
              <option value={3}>3 题</option>
              <option value={5}>5 题</option>
            </select>
          </div>

          <button
            type="button"
            onClick={startWithPlacement}
            className="cursor-pointer rounded-[2px] border border-vermilion bg-vermilion px-[22px] py-[11px] text-[13px] leading-[1.2] tracking-[0.06em] text-white transition-colors duration-200 hover:border-vermilion-deep hover:bg-vermilion-deep"
          >
            开始
          </button>
        </div>
      ) : null}
    </div>
  );
}
