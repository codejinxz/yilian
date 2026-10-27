import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import {
  Fragment,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { EmptyState, ErrorState, LoadingBlock, Notice } from "@/components/feedback";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { BankPeekDrawer } from "@/features/practice/bank-peek-drawer";
import {
  gradeCloze,
  gradeFree,
  normalizeAnswer,
  retypedMatchesReference,
} from "@/features/practice/grading";
import { enqueueAttempt, useOfflineQueueCount } from "@/features/practice/offline-queue";
import {
  clearDraft,
  loadDraft,
  type SentenceDraft,
  saveDraft,
} from "@/features/practice/practice-drafts";
import { RoundSummaryModal, type RoundTally } from "@/features/practice/session-summary";
import { useFocusScroll } from "@/hooks/use-focus-scroll";
import { useOnlineStatus } from "@/hooks/use-online-status";
import { ApiError } from "@/lib/api";
import { getBankSentences, getBanks } from "@/lib/api/content";
import {
  appealAnnotation,
  disputeAnnotation,
  getAttempt,
  getNextPractice,
  getReviews,
  submitAttempt,
} from "@/lib/api/practice";
import { queryKeys, todayQueryOptions } from "@/lib/query";
import type {
  AttemptRequest,
  AttemptResponse,
  GradingAnnotation,
  GradingResult,
  PracticeMode,
  PracticeSentence,
} from "@/lib/schemas/practice";
import { cn } from "@/lib/utils";

/**
 * /practice 的查询串契约：attemptId 回看、sentenceId+mode 指定句重练、
 * bankId/errors 来自今日页题库与错题 chips（validateSearch 已收敛形状，路由冻结）。
 */
type PracticeSearch = {
  attemptId?: string;
  sentenceId?: string;
  mode?: "CLOZE" | "FREE";
  bankId?: string;
  errors?: true;
};

interface Submission {
  request: AttemptRequest;
  sentence: PracticeSentence;
  result: GradingResult;
}

/**
 * linkish / kbd 不在本文件重复定义：styles.css 的 @layer components 已按原型逐值定义
 * `.linkish` 与 `kbd`（元素选择器），页面直接消费，字号差异用 text-[Npx] 工具类覆盖。
 */

/** 挖空序号角标：朱红底纸色字，紧跟在输入框前。 */
const BLANK_BADGE =
  "mr-1 inline-block rounded-[2px] bg-vermilion px-[3px] py-px align-[2px] font-mono text-[9px] font-semibold leading-none text-paper";

/** 难度代码 → 中文标签；未知代码原样展示（自建难度）。 */
const LEVEL_LABELS: Record<string, string> = {
  CET4: "四级",
  CET6: "六级",
  TEM4: "专四",
  TEM8: "专八",
  IELTS: "雅思",
  TOEFL: "托福",
  CUSTOM: "自建",
};

const SENTENCE_SNAPSHOT_PREFIX = "yilian.practice.sentence.";

/**
 * 缓存提交时的题干，刷新结果页时仍能显示中文原句与语块。
 * @param sentence 题目
 */
function storeSentenceSnapshot(sentence: PracticeSentence): void {
  try {
    sessionStorage.setItem(`${SENTENCE_SNAPSHOT_PREFIX}${sentence.id}`, JSON.stringify(sentence));
  } catch {
    // 隐私模式下 sessionStorage 可能失败；结果详情仍可通过接口恢复。
  }
}

/**
 * 读取题干快照；损坏或缺失时返回 null。
 * @param sentenceId 句子 id
 * @returns 题目或 null
 */
function readSentenceSnapshot(sentenceId: string): PracticeSentence | null {
  try {
    const raw = sessionStorage.getItem(`${SENTENCE_SNAPSHOT_PREFIX}${sentenceId}`);
    return raw ? (JSON.parse(raw) as PracticeSentence) : null;
  } catch {
    return null;
  }
}

/**
 * 展示用难度标签；空值返回 null 让 qmeta 省略该段。
 * @param level 难度代码
 * @returns 中文标签或 null
 */
function levelLabel(level: string | null | undefined): string | null {
  if (!level) {
    return null;
  }
  return LEVEL_LABELS[level] ?? level;
}

/**
 * 从判分结果里取「错点考点」列表：错批注的考点优先，没有批注时回退句子首考点。
 * @param annotations 判分批注
 * @param sentenceTags 句子考点
 * @returns 考点列表
 */
function weakTagsOf(annotations: GradingAnnotation[], sentenceTags: string[]): string[] {
  const wrong = annotations.filter((item) => !item.correct).map((item) => item.tag);
  return wrong.length > 0 ? wrong : sentenceTags.slice(0, 1);
}

/**
 * 生成题目中挖空语块的中文高亮；全部命中才使用高亮，否则退回纯文本，避免错位。
 * @param sentence 题目
 * @returns 可渲染的题干
 */
function ChinesePrompt({ sentence }: { sentence: PracticeSentence }): ReactNode {
  const positions = sentence.chunks
    .map((chunk) => ({ chunk, position: sentence.zh.indexOf(chunk.zh) }))
    .filter((item) => item.position >= 0)
    .sort((a, b) => a.position - b.position);
  const blankChunks = sentence.blank
    .map((index) => sentence.chunks.find((chunk) => chunk.index === index))
    .filter((chunk): chunk is NonNullable<typeof chunk> => Boolean(chunk));
  const canHighlight = blankChunks.every((chunk) =>
    positions.some((item) => item.chunk.index === chunk.index),
  );

  if (!canHighlight || positions.length === 0) {
    return <>{sentence.zh}</>;
  }

  const parts: ReactNode[] = [];
  let cursor = 0;
  for (const { chunk, position } of positions) {
    if (position < cursor) {
      continue;
    }
    if (position > cursor) {
      parts.push(sentence.zh.slice(cursor, position));
    }
    const blankIndex = sentence.blank.indexOf(chunk.index);
    if (blankIndex >= 0) {
      parts.push(
        <span
          key={`blank-${chunk.index}`}
          className="mx-0.5 border-b-[1.5px] border-vermilion px-0.5 pb-[3px]"
        >
          <i className={BLANK_BADGE}>{blankIndex + 1}</i>
          {chunk.zh}
        </span>,
      );
    } else {
      parts.push(chunk.zh);
    }
    cursor = position + chunk.zh.length;
  }
  if (cursor < sentence.zh.length) {
    parts.push(sentence.zh.slice(cursor));
  }
  return <>{parts}</>;
}

/**
 * 批注操作：申诉或立即排除。成功后只更新当前页的 overlay，接口本身不回写 attempt 批注。
 * @param props.attemptId 当前提交
 * @param props.annotation 批注
 * @param props.disabled 离线或未确认时禁用
 */
function AnnotationActions({
  attemptId,
  annotation,
  disabled,
}: {
  attemptId: string;
  annotation: GradingAnnotation;
  disabled: boolean;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState<"appeal" | "dispute" | null>(null);
  const [reason, setReason] = useState("");
  const [proposedAnswer, setProposedAnswer] = useState("");
  const [status, setStatus] = useState<"PENDING" | "ACCEPTED" | null>(null);

  const appeal = useMutation({
    mutationFn: () =>
      appealAnnotation(attemptId, annotation.key, {
        proposedAnswer: proposedAnswer.trim() || undefined,
        reason: reason.trim() || undefined,
      }),
    onSuccess: (result) => {
      setStatus(result.status === "PENDING" ? "PENDING" : "ACCEPTED");
      setOpen(null);
      void queryClient.invalidateQueries({ queryKey: ["practice", "wrong-questions"] });
      void queryClient.invalidateQueries({ queryKey: ["analytics"] });
    },
  });

  const dispute = useMutation({
    mutationFn: () =>
      disputeAnnotation(attemptId, annotation.key, {
        reason: reason.trim() || undefined,
      }),
    onSuccess: (result) => {
      setStatus(result.status === "ACCEPTED" ? "ACCEPTED" : "PENDING");
      setOpen(null);
      void queryClient.invalidateQueries({ queryKey: ["practice", "wrong-questions"] });
      void queryClient.invalidateQueries({ queryKey: ["analytics"] });
    },
  });

  if (status === "ACCEPTED") {
    return (
      <p className="mt-2 font-mono text-[10px] tracking-[0.06em] text-moss">
        已排除：不计入错题与统计
      </p>
    );
  }
  if (status === "PENDING") {
    return (
      <p className="mt-2 font-mono text-[10px] tracking-[0.06em] text-ink-faint">
        申诉已记录，等待复核；当前分数不变
      </p>
    );
  }

  return (
    <>
      {/* 原型 .appeal：正文行内、11px、朱红、下边线 rgba(178,58,46,.35)，两个入口并列。 */}
      <span className="text-[11px]">
        {" "}
        <button
          type="button"
          disabled={disabled}
          onClick={() => setOpen(open === "appeal" ? null : "appeal")}
          className="ml-1 cursor-pointer border-b border-vermilion/35 bg-transparent p-0 font-sans text-[11px] text-vermilion disabled:opacity-50"
        >
          我觉得也对
        </button>
        <button
          type="button"
          disabled={disabled}
          onClick={() => setOpen(open === "dispute" ? null : "dispute")}
          className="ml-1 cursor-pointer border-b border-vermilion/35 bg-transparent p-0 font-sans text-[11px] text-vermilion disabled:opacity-50"
        >
          不认同
        </button>
      </span>

      {open ? (
        <div className="mt-2.5 space-y-2 rounded-md border border-rule bg-paper-deep/40 p-2.5">
          {open === "appeal" ? (
            <input
              value={proposedAnswer}
              onChange={(event) => setProposedAnswer(event.target.value)}
              placeholder="你认为可接受的答案（可选）"
              className="w-full rounded border border-rule bg-surface px-2 py-1 text-xs text-ink outline-none focus:border-vermilion"
            />
          ) : null}
          <Textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            rows={2}
            placeholder={open === "appeal" ? "为什么也可以（可选）" : "为什么不认同（可选）"}
            className="text-xs"
          />
          <div className="flex items-center gap-2.5">
            <Button
              size="sm"
              variant={open === "dispute" ? "danger" : "primary"}
              disabled={appeal.isPending || dispute.isPending}
              onClick={() => (open === "appeal" ? appeal.mutate() : dispute.mutate())}
            >
              {open === "appeal" ? "提交申诉" : "排除这条"}
            </Button>
            <Button size="sm" variant="quiet" onClick={() => setOpen(null)}>
              取消
            </Button>
          </div>
          {appeal.isError || dispute.isError ? (
            <p role="alert" className="text-xs text-vermilion-deep">
              {(appeal.error ?? dispute.error)?.message}
            </p>
          ) : null}
        </div>
      ) : null}
    </>
  );
}

/**
 * 练习页：一屏一句的「稿纸」。布局、文案与交互与原型 view-practice 保持一致——
 * 会话条 + 页顶进度条 + 模式页签 + 双栏（主练习区 640px / 批注栏 236px，<900px 折叠单栏）。
 * 游客与登录用户同构：数据全部经 lib/api 的本地适配层或 mock 应答。
 */
export function PracticePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const online = useOnlineStatus();
  const pendingQueue = useOfflineQueueCount();
  const search = useSearch({ strict: false }) as PracticeSearch;
  const attemptId = typeof search.attemptId === "string" ? search.attemptId : undefined;
  const searchSentenceId =
    typeof search.sentenceId === "string" && search.sentenceId.length > 0
      ? search.sentenceId
      : undefined;
  const searchMode = search.mode;
  // 今日页题库 chips 的深链：限定在该题库内取题。
  const searchBankId = typeof search.bankId === "string" ? search.bankId : undefined;
  // 今日页「错题」chip 的深链：只在错题里取下一题。
  const errorsMode = search.errors === true;

  const [mode, setMode] = useState<PracticeMode>(() =>
    searchMode === "FREE" || searchMode === "CLOZE" ? searchMode : "CLOZE",
  );
  const [bankId, setBankId] = useState<string | undefined>(undefined);
  const [excludeIds, setExcludeIds] = useState<string[]>([]);
  const [drillTag, setDrillTag] = useState<string | undefined>(undefined);
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [freeText, setFreeText] = useState("");
  const [hintLevel, setHintLevel] = useState(0);
  const [revealConfirm, setRevealConfirm] = useState(false);
  const [retryText, setRetryText] = useState("");
  const [retryError, setRetryError] = useState<string | null>(null);
  const [optimistic, setOptimistic] = useState<AttemptResponse | null>(null);
  const [pendingSubmission, setPendingSubmission] = useState<Submission | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [offlineNotice, setOfflineNotice] = useState<string | null>(null);
  const [restoredSentence, setRestoredSentence] = useState<PracticeSentence | null>(null);
  const [round, setRound] = useState<RoundTally>({ done: 0, correct: 0, weakTags: {} });
  const [summaryOpen, setSummaryOpen] = useState(false);
  /** 题库抽屉（qmeta「题库」入口）的开关。 */
  const [peekOpen, setPeekOpen] = useState(false);
  /** 音标展示开关；仅当句子携带 ipa 时入口才渲染，切换状态随换句重置。 */
  const [ipaVisible, setIpaVisible] = useState(false);
  /** 模型复核占位：本地把展示分数 +10，不调用模型、不改库（M3 接入真实复核）。 */
  const [reviewedScore, setReviewedScore] = useState<number | null>(null);
  /** 本会话内改对已确认（RETRY 成功）：豁免原 attempt 的 retryRequired，换句/重答即重置。 */
  const [retryFixed, setRetryFixed] = useState(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const startedAtRef = useRef(Date.now());
  const toastTimerRef = useRef<number | null>(null);
  const skeletonRef = useRef<HTMLParagraphElement | null>(null);
  const resultRef = useRef<HTMLElement | null>(null);
  const activeSentenceIdRef = useRef<string | null>(null);

  /** 抽屉关闭回调；useCallback 固定引用，避免抽屉内 Escape 监听随父组件重渲染反复挂卸。 */
  const closePeek = useCallback(() => setPeekOpen(false), []);

  /** 轻提示：与原型 toast 同款，1.8s 自动消失。 */
  function toast(message: string) {
    setToastMessage(message);
    if (toastTimerRef.current !== null) {
      window.clearTimeout(toastTimerRef.current);
    }
    toastTimerRef.current = window.setTimeout(() => setToastMessage(null), 1800);
  }

  const todayQuery = useQuery(todayQueryOptions);
  const banksQuery = useQuery({
    queryKey: ["content", "banks", "practice-select"],
    queryFn: ({ signal }) => getBanks({ page: 1, size: 100, status: "PUBLISHED" }, signal),
    staleTime: 60_000,
  });
  const publishedBanks = (banksQuery.data?.items ?? []).filter(
    (bank) => bank.status === "PUBLISHED",
  );
  // 缺省练第一个公开题库（与原型 official 缺省一致）；题库拉取失败时退回全部题库并集。
  // 深链 bankId（今日页 chips）优先于缺省、次于本页手选；题库列表已加载且不含该 id
  // （下架/私有）时忽略，避免取题命中空题库打成空态。
  const searchBankValid =
    !searchBankId ||
    publishedBanks.length === 0 ||
    publishedBanks.some((bank) => bank.id === searchBankId);
  const effectiveBankId =
    bankId ?? (searchBankValid ? searchBankId : undefined) ?? publishedBanks[0]?.id;
  const selectedBankTotal =
    publishedBanks.find((bank) => bank.id === effectiveBankId)?.sentenceCount ?? null;

  const bankSentencesQuery = useQuery({
    queryKey: ["content", "bank-sentences", effectiveBankId ?? ""],
    queryFn: ({ signal }) =>
      getBankSentences(effectiveBankId ?? "", { sinceVersion: 0, limit: 200 }, signal),
    enabled: Boolean(effectiveBankId),
    retry: false,
    staleTime: 60_000,
  });

  // 抽屉里的「已练」标记：有非 NEW 复习卡即算练过（引擎「错才入队」，全对首练不建卡，
  // 与队列口径一致；错题行必带 LEARNING 卡，天然覆盖）。只在打开抽屉时拉取；
  // size 上限 100 是 mock/服务端分页校验的共同约束。
  const practicedQuery = useQuery({
    queryKey: queryKeys.reviews({ page: 1, size: 100 }),
    queryFn: ({ signal }) => getReviews({ page: 1, size: 100 }, signal),
    enabled: peekOpen,
    staleTime: 30_000,
  });
  // 「在队列」口径：错才入队（state !== "NEW"），全对首练不建卡——与抽屉标签语义一致。
  const queuedIds = useMemo(
    () =>
      new Set(
        (practicedQuery.data?.items ?? [])
          .filter((item) => item.state !== "NEW")
          .map((item) => item.sentenceId),
      ),
    [practicedQuery.data],
  );

  // 抽屉句子行：题库全量句子（过滤删除行与墓碑），当前句在 qidx 定位里已有同源数据。
  const peekSentences = useMemo(
    () =>
      (bankSentencesQuery.data?.items ?? [])
        .filter(
          (item): item is typeof item & { zh: string } =>
            !item.deleted && typeof item.zh === "string" && item.zh.length > 0,
        )
        .map((item) => ({ id: item.id, zh: item.zh, level: item.level ?? null })),
    [bankSentencesQuery.data],
  );
  const peekBank = publishedBanks.find((bank) => bank.id === effectiveBankId) ?? null;

  // 错题模式不带「缺省回落」的题库：缺省 bankId 会把错题池静默收窄到第一个题库，
  // 其他题库的错题不可达；用户显式选择（下拉切换或深链带入）时仍尊重其筛选。
  const selectedBankId = errorsMode
    ? (bankId ?? (searchBankValid ? searchBankId : undefined))
    : effectiveBankId;
  const nextParams = {
    mode,
    ...(selectedBankId ? { bankId: selectedBankId } : {}),
    ...(searchSentenceId ? { sentenceId: searchSentenceId } : {}),
    ...(drillTag ? { tag: drillTag } : {}),
    ...(excludeIds.length > 0 ? { excludeIds } : {}),
  };
  const baseNextKey = queryKeys.nextPractice(nextParams);
  // queryKeys.nextPractice（lib/query.ts）暂无 errors 维度；键尾追加 "errors" 标记位
  // 区分错题取题与同参数普通取题，避免两者共用同一份缓存。
  // TODO: 将 errors 维度收进 queryKeys.nextPractice 的参数类型。
  const nextQueryKey = errorsMode ? ([...baseNextKey, "errors"] as const) : baseNextKey;

  const nextQuery = useQuery({
    queryKey: nextQueryKey,
    queryFn: ({ signal }) =>
      getNextPractice({ ...nextParams, ...(errorsMode ? { errors: true } : {}) }, signal),
    enabled: !attemptId && !optimistic,
  });

  const attemptQuery = useQuery({
    queryKey: queryKeys.attempt(attemptId ?? ""),
    queryFn: ({ signal }) => getAttempt(attemptId ?? "", signal),
    enabled: Boolean(attemptId),
  });

  const attempt = attemptQuery.data ?? optimistic;
  // 快照优先：提交成功后 optimistic 清空与 attemptId 就位之间，nextQuery 会短暂
  // re-enable；staleTime 30s 过后该缓存触发后台 refetch，若让 nextQuery.data 优先，
  // 取回的「下一句」会覆盖刚提交的题干，造成串句与 RETRY 重判污染。
  // restoredSentence 仅在 attempt 加载成功时有值（新题流 attemptId 为空、恒为 null），
  // 因此新题取题不受影响；RETRY 请求构建也已改用快照句。
  const sentence = restoredSentence ?? nextQuery.data?.sentence ?? null;
  const displayMode = attempt?.mode ?? mode;
  const displaySentence = sentence;

  useFocusScroll(sentence?.id);

  useEffect(() => {
    if (!attemptId) {
      setRestoredSentence(null);
      return;
    }
    if (attemptQuery.data) {
      setRestoredSentence(readSentenceSnapshot(attemptQuery.data.sentenceId));
      setMode(attemptQuery.data.mode);
    }
  }, [attemptId, attemptQuery.data]);

  // 复习页/今日页的类型化深链在页内导航时不会重挂载组件，search 变化需要同步到模式状态。
  useEffect(() => {
    if (searchMode === "CLOZE" || searchMode === "FREE") {
      setMode(searchMode);
    }
  }, [searchMode]);

  // 新题出现时重置输入与计时，并恢复本句草稿（标签页崩溃后不丢已输入内容）。
  // 依赖收窄到 id：重取同一题会更换对象引用，直接依赖 sentence 会在作答中途误清输入。
  const sentenceId = sentence?.id;
  useEffect(() => {
    if (sentenceId === undefined) {
      return;
    }
    startedAtRef.current = Date.now();
    const draft = loadDraft(sentenceId);
    setAnswers(draft.slots);
    setFreeText(draft.free);
    setHintLevel(0);
    setRevealConfirm(false);
    setRetryText("");
    setRetryError(null);
    setReviewedScore(null);
    setRetryFixed(false);
    setIpaVisible(false);
  }, [sentenceId]);

  // 判分结果渲染后把焦点移到结果块（原型 $('#result').focus()）：读屏立即播报结果，
  // 键盘用户不必 Tab 寻址；先平滑滚到可视再无滚动聚焦，避免移动端把结果顶出视口。
  useEffect(() => {
    if (!attempt) {
      return;
    }
    const node = resultRef.current;
    if (!node) {
      return;
    }
    node.scrollIntoView({ behavior: "smooth", block: "nearest" });
    node.focus({ preventScroll: true });
  }, [attempt]);

  // 作答途中的每次击键都落盘；已提交（attempt 存在）后不再写草稿。
  useEffect(() => {
    if (!sentenceId || attempt) {
      return;
    }
    const draft: SentenceDraft = { slots: answers, free: freeText };
    saveDraft(sentenceId, draft);
  }, [answers, freeText, sentenceId, attempt]);

  // 记录当前句 id 供卸载清理使用；离开练习页清掉草稿，符合「离开/提交清除」的约定。
  useEffect(() => {
    activeSentenceIdRef.current = sentenceId ?? null;
  }, [sentenceId]);
  useEffect(() => {
    return () => {
      const active = activeSentenceIdRef.current;
      if (active) {
        clearDraft(active);
      }
    };
  }, []);

  const submitMutation = useMutation({
    mutationFn: (submission: Submission) => submitAttempt(submission.request),
    onSuccess: (response, submission) => {
      storeSentenceSnapshot(submission.sentence);
      clearDraft(submission.request.sentenceId);
      queryClient.setQueryData(queryKeys.attempt(response.attemptId), response);
      setPendingSubmission(null);
      setSubmitError(null);
      setOfflineNotice(null);
      countRound(
        submission.request.kind,
        response.score,
        response.result.annotations,
        submission.sentence.tags,
      );
      if (submission.request.kind === "RETRY") {
        // 改对成功不换视图（与原型 checkRetry 行为一致）：保留原 attempt 判分与「改对才过」块，
        // 块内显示苔绿「已修复」；「下一句」由 retryFixed 豁免放行。乐观态不清——
        // 乐观-FIRST 边缘下它就是原判分视图。刷新后回到原 attempt 仍是待改对态：
        // 判分 append-only，改对记录已在库、原判分不覆盖，此为可接受行为。
        setRetryFixed(true);
        toast("已修复，可以下一句了");
      } else {
        setOptimistic(null);
        void navigate({
          to: "/practice",
          // 错题模式下结果回看仍带 errors=1，刷新/回跳后「下一句」继续在错题池取题。
          search: { attemptId: response.attemptId, ...(errorsMode ? { errors: true } : {}) },
          replace: true,
        });
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.today });
      void queryClient.invalidateQueries({ queryKey: ["practice", "reviews"] });
    },
    onError: (error, submission) => {
      if (error instanceof ApiError && error.status === 0) {
        enqueueAttempt(submission.request);
        setPendingSubmission(null);
        setOfflineNotice("当前离线：本次作答已保存在本机，联网后会自动补交，不会丢。");
        return;
      }
      setPendingSubmission(submission);
      setSubmitError(error.message);
    },
  });

  /**
   * 累计「本轮」内存态计数：首次提交与看答案各计 1，重打不计；错题按批注考点记账。
   * @param kind 提交类型
   * @param score 判分（REVEAL 为 null）
   * @param annotations 判分批注
   * @param sentenceTags 句子考点（无错批注时回退）
   */
  function countRound(
    kind: AttemptRequest["kind"],
    score: number | null,
    annotations: GradingAnnotation[],
    sentenceTags: string[],
  ) {
    if (kind === "RETRY") {
      return;
    }
    setRound((current) => {
      const weakTags = { ...current.weakTags };
      if (kind === "FIRST" && score !== null && score < 100) {
        for (const tag of weakTagsOf(annotations, sentenceTags)) {
          weakTags[tag] = (weakTags[tag] ?? 0) + 1;
        }
      }
      return {
        done: current.done + 1,
        correct: current.correct + (score === 100 ? 1 : 0),
        weakTags,
      };
    });
  }

  /**
   * 计算本地判分；填空强制本地、整句给本地粗判作为即时反馈。
   * @returns 判分结果
   */
  function localResult(): GradingResult | null {
    if (!sentence) {
      return null;
    }
    if (mode === "CLOZE") {
      return gradeCloze({
        sentence: sentence.zh,
        chunks: sentence.chunks,
        blank: sentence.blank,
        answers: sentence.blank.map((chunkIndex) => answers[chunkIndex] ?? ""),
      });
    }
    return gradeFree({ sentence: sentence.zh, ref: sentence.ref, text: freeText });
  }

  /**
   * 组装 attempt 请求。
   * @param kind 提交类型
   * @param retryOfId 改错指向的原提交
   * @param override 覆盖答案（重打参考译文时使用）
   * @returns attempt 请求
   */
  function buildRequest(
    kind: "FIRST" | "RETRY" | "REVEAL",
    retryOfId: string | null,
    override?: { slots?: Array<{ chunkIndex: number; value: string }>; text?: string },
  ): AttemptRequest {
    const requestMode = attempt?.mode ?? mode;
    const activeSentence = sentence;
    if (!activeSentence) {
      throw new Error("题目尚未加载");
    }
    const slots =
      override?.slots ??
      (requestMode === "CLOZE"
        ? activeSentence.blank.map((chunkIndex) => {
            if (kind === "REVEAL") {
              return { chunkIndex, value: "" };
            }
            return { chunkIndex, value: answers[chunkIndex] ?? "" };
          })
        : []);
    const answer =
      requestMode === "CLOZE"
        ? {
            schemaVersion: 1 as const,
            mode: "CLOZE" as const,
            ...(kind === "REVEAL" ? { revealed: true } : {}),
            slots: kind === "REVEAL" ? [] : slots,
          }
        : {
            schemaVersion: 1 as const,
            mode: "FREE" as const,
            ...(kind === "REVEAL" ? { revealed: true } : {}),
            text: kind === "REVEAL" ? "" : (override?.text ?? freeText),
          };
    return {
      attemptId: crypto.randomUUID(),
      sentenceId: activeSentence.id,
      sentenceVersionId: activeSentence.sentenceVersionId,
      mode: requestMode,
      kind,
      retryOfId,
      answer,
      hintsUsed: Math.min(3, hintLevel),
      durationMs: Math.min(86_400_000, Math.max(0, Date.now() - startedAtRef.current)),
      clientCreatedAt: new Date().toISOString(),
    };
  }

  /**
   * 构造离线/错误时的本地结果，让用户马上看到批改且可读。
   * @param request 请求
   * @param result 本地判分
   * @returns 与接口响应同形的结果
   */
  function localAttempt(request: AttemptRequest, result: GradingResult): AttemptResponse {
    return {
      attemptId: request.attemptId,
      sentenceId: request.sentenceId,
      sentenceVersionId: request.sentenceVersionId,
      mode: request.mode,
      kind: request.kind,
      score: result.score,
      maxScore: 100,
      result,
      grading: {
        id: "local",
        sequence: 1,
        grader: "LOCAL",
        normalizerVersion: "local-norm-1",
        rubricVersion: "local-rubric-1",
        createdAt: new Date().toISOString(),
      },
      review: null,
      reference: { ref: sentence?.ref ?? "", note: sentence?.note ?? null },
      nextAction: {
        retryRequired: result.score !== null && result.score < 100 && request.kind === "FIRST",
        canReveal: request.kind !== "REVEAL",
      },
      replayed: false,
    };
  }

  /**
   * 提交当前作答；离线或网络失败时落本地队列并展示本地判分。
   */
  function handleSubmit() {
    if (attempt || submitMutation.isPending) {
      return;
    }
    const result = localResult();
    if (!sentence || !result) {
      return;
    }
    if (mode === "FREE" && freeText.trim() === "") {
      toast("先写一句再提交");
      return;
    }
    let request: AttemptRequest;
    try {
      request = buildRequest("FIRST", null);
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : "题目尚未加载");
      return;
    }
    const submission = { request, sentence, result };
    setSubmitError(null);
    setOptimistic(localAttempt(request, result));

    if (!online) {
      enqueueAttempt(request);
      countRound(request.kind, result.score, result.annotations, sentence.tags);
      setOfflineNotice("当前离线：本次作答已保存在本机，联网后会自动补交，不会丢。");
      return;
    }
    submitMutation.mutate(submission);
  }

  /**
   * 提交改对/看答案；这两种动作都走同一 attempt 接口，仅 kind 不同。
   * REVEAL 不依赖既有 attempt——新题上直接「看答案」是主路径，本地引擎与 mock
   * 都只校验 answer.revealed 标记（retryOfId 本就传 null）；RETRY 必须挂回原提交，
   * attempt 缺失时拒绝。
   * @param kind RETRY 或 REVEAL
   */
  function submitFollowUp(kind: "RETRY" | "REVEAL") {
    if (!sentence) {
      return;
    }
    let request: AttemptRequest;
    let result: GradingResult;
    try {
      if (kind === "RETRY") {
        if (!attempt) {
          return;
        }
        if (!retypedMatchesReference(attempt.reference.ref, retryText)) {
          setRetryError("还有差异，再对照一遍。大小写、标点与多余空格可以忽略，但用词要一致。");
          return;
        }
        setRetryError(null);
        const slots =
          attempt.mode === "CLOZE"
            ? sentence.blank.map((chunkIndex) => {
                const chunk = sentence.chunks.find((item) => item.index === chunkIndex);
                return { chunkIndex, value: chunk?.answers[0] ?? "" };
              })
            : undefined;
        request = buildRequest("RETRY", attempt.attemptId, {
          ...(slots ? { slots } : {}),
          ...(attempt.mode === "FREE" ? { text: retryText } : {}),
        });
        result = localResult() ?? attempt.result;
      } else {
        setRetryError(null);
        request = buildRequest("REVEAL", null);
        result = {
          schemaVersion: 1 as const,
          score: null,
          maxScore: 100,
          hitCount: 0,
          totalCount: 0,
          annotations: [],
          summary: "已看答案，记为学习中",
        };
      }
    } catch (error) {
      setRetryError(error instanceof Error ? error.message : "题目尚未加载");
      return;
    }
    // RETRY 不替换乐观态：改对过程中与成功后都要保留原判分视图（改对成功不换视图，
    // 见 submitMutation.onSuccess）；REVEAL（尤其新题主路径）没有既有 attempt，
    // 需要乐观渲染看答案结果。
    if (kind === "REVEAL") {
      setOptimistic(localAttempt(request, result));
    }
    if (!online) {
      enqueueAttempt(request);
      countRound(request.kind, result.score, result.annotations, sentence.tags);
      setOfflineNotice("当前离线：本次操作已保存在本机，联网后会自动补交。");
      return;
    }
    submitMutation.mutate({ request, sentence, result });
  }

  /**
   * 判断是否允许下一题；答错且未重打/看答案时挡住。
   * retryFixed：本会话内已改对（RETRY 成功），豁免原 attempt 的 retryRequired——
   * 判分 append-only，原记录不改，豁免只在内存态生效。
   * @returns 是否可前进
   */
  function canAdvance(): boolean {
    if (!attempt) {
      return false;
    }
    return retryFixed || !attempt.nextAction.retryRequired;
  }

  /**
   * 进入下一题：清结果、记录排除题，重新拉取；drillTag 让下一次取题按考点筛选。
   * @param nextDrillTag 下一步训练的考点（不传即清除筛选）
   */
  function advance(nextDrillTag?: string) {
    if (!canAdvance()) {
      toast("先把这句改对，或点「稍后再练」");
      return;
    }
    const currentId = attempt?.sentenceId;
    setOptimistic(null);
    setPendingSubmission(null);
    setSubmitError(null);
    setOfflineNotice(null);
    setExcludeIds(currentId ? [currentId] : []);
    setDrillTag(nextDrillTag);
    setAnswers({});
    setFreeText("");
    setHintLevel(0);
    setRetryText("");
    setRetryError(null);
    setReviewedScore(null);
    setRetryFixed(false);
    if (attemptId || searchSentenceId) {
      // 回跳时保留错题模式：清掉指定句/回看参数后，继续在错题池里取下一题。
      void navigate({ to: "/practice", search: errorsMode ? { errors: true } : {}, replace: true });
    }
  }

  /**
   * 切换模式；有未修复的错题时挡住，避免绕开「改对才过」。
   * @param next 目标模式
   */
  function switchMode(next: PracticeMode) {
    if ((attempt?.mode ?? mode) === next) {
      return;
    }
    if (attempt?.nextAction.retryRequired) {
      toast("先把这句改对，或点「稍后再练」");
      return;
    }
    setMode(next);
    setHintLevel(0);
    if (attempt || attemptId) {
      // 已有结果时切模式：回到同一句的新模式作答态（结果随 attempt 清掉）。
      setOptimistic(null);
      setPendingSubmission(null);
      setSubmitError(null);
      setOfflineNotice(null);
      setRevealConfirm(false);
      setReviewedScore(null);
      setRetryFixed(false);
      if (attemptId) {
        // 与 advance 一致：清结果参数但保留错题模式。
        void navigate({
          to: "/practice",
          search: errorsMode ? { errors: true } : {},
          replace: true,
        });
      }
    }
  }

  /**
   * 切换题库；未修复的错题同样挡住。指定句深链随切换清除，否则取题参数会继续命中旧句。
   * @param nextBankId 目标题库 id
   */
  function switchBank(nextBankId: string) {
    if (!nextBankId || nextBankId === effectiveBankId) {
      return;
    }
    if (attempt?.nextAction.retryRequired) {
      toast("先把这句改对，或点「稍后再练」");
      return;
    }
    setBankId(nextBankId);
    setExcludeIds([]);
    setDrillTag(undefined);
    if (attempt || attemptId || searchSentenceId) {
      setOptimistic(null);
      setPendingSubmission(null);
      setSubmitError(null);
      setOfflineNotice(null);
      setRevealConfirm(false);
      setReviewedScore(null);
      setRetryFixed(false);
      // 清掉 URL 里过期的深链 bankId（本页手选已接管），错题模式随回跳保留。
      void navigate({ to: "/practice", search: errorsMode ? { errors: true } : {}, replace: true });
    }
  }

  /**
   * 模型复核（本地占位）：只把展示分数 +10 并给出原型复核文案，不调用模型、不改判分记录。
   */
  function handleModelReview() {
    if (!attempt || attempt.score === null || reviewedScore !== null) {
      return;
    }
    setReviewedScore(Math.min(100, attempt.score + 10));
    toast("已复核，原分保留");
  }

  /**
   * 用 Web Speech 朗读参考译文；与原型一致 en-US、0.9 倍速。
   * @param text 要朗读的英文文本
   */
  function speakRef(text: string) {
    if (!("speechSynthesis" in window)) {
      toast("当前浏览器不支持朗读");
      return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "en-US";
    utterance.rate = 0.9;
    window.speechSynthesis.speak(utterance);
  }

  /**
   * 填空槽位 Enter：跳下一空，最后一空直接提交（与原型一致，IME 组合中不拦截）。
   * @param event 键盘事件
   * @param current 当前输入框；在全部槽位序列里定位下一个
   */
  function handleSlotKeyDown(
    event: ReactKeyboardEvent<HTMLInputElement>,
    current: HTMLInputElement,
  ) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return;
    }
    if (event.key !== "Enter") {
      return;
    }
    event.preventDefault();
    const inputs = Array.from(
      skeletonRef.current?.querySelectorAll<HTMLInputElement>("input") ?? [],
    );
    const next = inputs[inputs.indexOf(current) + 1];
    if (next) {
      next.focus();
      return;
    }
    handleSubmit();
  }

  /**
   * 使用提示；第三级在提示行直接展示规范答案，由用户自己抄写作答——
   * 自动填入会让 ⌘↵ 零成本得到满分，弱化提示的练习语义（与原型一致：只展示不代填）。
   * 命名不以 use 开头：这是普通事件处理函数而非 Hook，避免触发 Hook 规则误判。
   */
  function cycleHint() {
    setHintLevel((current) => Math.min(3, current + 1));
  }

  /**
   * 回到未提交状态重新作答；不清空已经输入的答案。
   */
  function backToEdit() {
    setOptimistic(null);
    setPendingSubmission(null);
    setSubmitError(null);
    setOfflineNotice(null);
    setRevealConfirm(false);
    setRetryFixed(false);
  }

  /**
   * 从题库抽屉跳到指定句练习（原型 openBankPeek 的 data-peek 行为）。
   * 有意不挡 retryRequired：跳走不等于把当前句「改对」，错句仍在学习队列里明天召回，
   * 原型抽屉同样允许直接跳句——与「下一句」按钮的放行门槛是两个语义。
   * 跳句是显式的单句选择，同时结束错题模式（search 被整体替换）。
   * @param targetSentenceId 目标句 id
   */
  function peekJump(targetSentenceId: string) {
    if (targetSentenceId === sentenceId) {
      setPeekOpen(false);
      return;
    }
    setPeekOpen(false);
    setOptimistic(null);
    setPendingSubmission(null);
    setSubmitError(null);
    setOfflineNotice(null);
    setRevealConfirm(false);
    setRetryError(null);
    setRetryFixed(false);
    void navigate({
      to: "/practice",
      // 保留当前题库：跳到的句子仍属该题库，缺省会话条选择器与进度序号才不会错位。
      search: {
        sentenceId: targetSentenceId,
        mode,
        ...(effectiveBankId ? { bankId: effectiveBankId } : {}),
      },
    });
  }

  // 提交后的批改视图数据源：以 attempt 批注为准（刷新恢复后答案状态为空也能还原笔迹）；
  // 逐词命中/错误标记由骨架渲染时按批注现算，这里只还原各挖空的笔迹文本。
  const submittedSlots = useMemo(() => {
    if (!attempt || !displaySentence || displayMode !== "CLOZE") {
      return null;
    }
    const byChunk = new Map(
      attempt.result.annotations.map((item) => [item.chunkIndex, item] as const),
    );
    const slots: Record<number, string> = {};
    for (const chunkIndex of displaySentence.blank) {
      const annotation = byChunk.get(chunkIndex);
      const canonical =
        displaySentence.chunks.find((item) => item.index === chunkIndex)?.answers[0] ?? "";
      if (annotation) {
        slots[chunkIndex] = annotation.received;
      } else {
        // REVEAL 没有批注：展示规范答案（已看答案即学习态）。
        slots[chunkIndex] = attempt.kind === "REVEAL" ? canonical : "";
      }
    }
    return slots;
  }, [attempt, displaySentence, displayMode]);

  const submittedFree = useMemo(() => {
    if (!attempt || displayMode !== "FREE") {
      return null;
    }
    const fromAnnotation = attempt.result.annotations.find((item) =>
      item.key.startsWith("free:"),
    )?.received;
    // 全对时 gradeFree 不产生批注，回退到页面状态里的提交文本。
    return fromAnnotation ?? (attempt.kind === "RETRY" ? retryText : freeText);
  }, [attempt, displayMode, retryText, freeText]);

  // 提示行文案与原型一致：首字母 → 词数 → 答案，逐级展开。
  const hintLine = useMemo(() => {
    if (!displaySentence || hintLevel === 0) {
      return "";
    }
    const parts = displaySentence.blank.map((chunkIndex, position) => {
      const canonical =
        displaySentence.chunks.find((item) => item.index === chunkIndex)?.answers[0] ?? "";
      if (hintLevel === 1) {
        return `${position + 1}. ${canonical.slice(0, 1)}…`;
      }
      if (hintLevel === 2) {
        return `${position + 1}. ${canonical.split(/\s+/).filter(Boolean).length} 词`;
      }
      return `${position + 1}. ${canonical}`;
    });
    const label = hintLevel === 1 ? "首字母" : hintLevel === 2 ? "词数" : "答案";
    return `${label}：${parts.join("   ")}`;
  }, [displaySentence, hintLevel]);

  // 快捷键经 ref 间接调用，监听器只注册一次，闭包却始终拿到最新状态。
  const submitShortcutRef = useRef<() => void>(() => {});
  const modeShortcutRef = useRef<(next: PracticeMode) => void>(() => {});
  submitShortcutRef.current = () => {
    if (attempt) {
      if (canAdvance()) {
        advance();
      } else {
        toast("先把这句改对，或点「稍后再练」");
      }
      return;
    }
    handleSubmit();
  };
  modeShortcutRef.current = switchMode;
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing) {
        return;
      }
      if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
        event.preventDefault();
        submitShortcutRef.current();
        return;
      }
      if (event.metaKey || event.ctrlKey || (event.key !== "1" && event.key !== "2")) {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLElement &&
        (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable)
      ) {
        return;
      }
      event.preventDefault();
      modeShortcutRef.current(event.key === "1" ? "CLOZE" : "FREE");
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, []);

  if (attemptId && attemptQuery.isPending && !optimistic) {
    return (
      <div className="mx-auto max-w-3xl py-10">
        <LoadingBlock label="正在恢复上次批改…" rows={4} />
      </div>
    );
  }

  if (attemptId && attemptQuery.isError && !optimistic) {
    return (
      <div className="mx-auto max-w-3xl py-10">
        <ErrorState error={attemptQuery.error} onRetry={() => void attemptQuery.refetch()} />
        <div className="mt-4">
          <Button
            variant="ghost"
            onClick={() => {
              void navigate({
                to: "/practice",
                search: errorsMode ? { errors: true } : {},
                replace: true,
              });
              setOptimistic(null);
            }}
          >
            开始新题
          </Button>
        </div>
      </div>
    );
  }

  if (!attemptId && nextQuery.isPending) {
    return (
      <div className="mx-auto max-w-3xl py-10">
        <LoadingBlock label="正在取下一题…" rows={4} />
      </div>
    );
  }

  if (!attemptId && nextQuery.isError) {
    return (
      <div className="mx-auto max-w-3xl py-10">
        <ErrorState error={nextQuery.error} onRetry={() => void nextQuery.refetch()} />
      </div>
    );
  }

  if (!attemptId && nextQuery.data === null && !attempt) {
    return (
      <div className="mx-auto max-w-3xl py-10">
        <EmptyState
          title={errorsMode ? "错题清完了" : "今天没有可练的题"}
          description={
            errorsMode
              ? "最近做错的句子都已修复或被排除。可以回今日继续新题，到期错题明天还会出现。"
              : "V1 不做无限题库。你可以去复习页重置一张卡，或等下一批到期。"
          }
          action={
            <Link to="/review" className="text-sm text-vermilion underline underline-offset-4">
              去复习队列
            </Link>
          }
        />
      </div>
    );
  }

  const isReveal = attempt?.kind === "REVEAL";
  const score = attempt?.score ?? null;
  const missedCount = attempt
    ? attempt.result.annotations.filter((item) => !item.correct).length
    : 0;
  const advancable = canAdvance();
  const disabledActions = !online || Boolean(optimistic);
  const dueCount = todayQuery.data?.dueCount ?? 0;
  const goal = todayQuery.data?.goal ?? null;

  // 进度条位置：优先按当前句在所选题库中的序号（与原型 01/08 展示一致）；
  // 指定句深链不属于当前题库或题库未加载时，回退为本轮进度（done/goal）。
  const bankItems = bankSentencesQuery.data?.items ?? null;
  const bankTotal = selectedBankTotal ?? bankItems?.length ?? null;
  const bankPos =
    displaySentence && bankItems
      ? bankItems.findIndex((item) => item.id === displaySentence.id) + 1
      : 0;
  const qidx =
    bankPos > 0 && bankTotal
      ? `${String(bankPos).padStart(2, "0")} / ${String(bankTotal).padStart(2, "0")}`
      : null;
  let progressRatio: number | null = null;
  if (bankPos > 0 && bankTotal) {
    progressRatio = (attempt ? bankPos : bankPos - 1) / bankTotal;
  } else if (goal && goal > 0) {
    progressRatio = Math.min(round.done, goal) / goal;
  }
  const progressWidth = `${Math.min(100, Math.max(0, Math.round((progressRatio ?? 0) * 1000) / 10))}%`;

  // 批改视图的展示分与文案；模型复核是本地占位，只改展示不改记录。
  const firstWrong = attempt?.result.annotations.find((item) => !item.correct) ?? null;
  const firstWrongZh =
    firstWrong && displaySentence
      ? (displaySentence.chunks.find((item) => item.index === firstWrong.chunkIndex)?.zh ?? null)
      : null;
  const displayScore = reviewedScore ?? (isReveal ? "—" : score);
  const displayNote =
    reviewedScore !== null
      ? `模型复核：整体可懂，${
          firstWrongZh ? `「${firstWrongZh}」这一处` : "部分表达"
        }建议按参考译文调整语序或搭配。`
      : isReveal
        ? "已看答案，本题记为学习中，不计入正确率。"
        : score === 100
          ? "全对。这一句可以进复习队列了。"
          : missedCount > 0
            ? `还差 ${missedCount} 处。先看批注，再重写一遍。`
            : "细节还要收一收。";

  // drill 行：答错的首次提交按错点考点推荐再练；看答案（isReveal）同样给出，
  // 与原型 revealCloze 的 renderDrill(s.tags) 一致——此时没有错批注，直接取句子首考点。
  const drillTagLabel = attempt
    ? isReveal
      ? (displaySentence?.tags[0] ?? null)
      : (attempt.score ?? 0) < 100 && attempt.kind === "FIRST"
        ? (firstWrong?.tag ?? displaySentence?.tags[0] ?? null)
        : null
    : null;

  const skeletonFontSize = displaySentence
    ? displaySentence.ref.length > 70
      ? "15px"
      : displaySentence.ref.length > 50
        ? "17px"
        : "20px"
    : "20px";

  return (
    <div className="relative mx-auto w-full max-w-[1000px]">
      {/* 页顶进度条：提交前 (当前第几题-1)/N，判分后 pos/N，宽度过渡 0.4s（贝塞尔同原型）。 */}
      <div className="fixed inset-x-0 top-0 z-20 h-[2px]" aria-hidden="true">
        <div
          className="h-full bg-vermilion transition-[width] duration-[400ms] ease-[cubic-bezier(.2,.8,.2,1)]"
          style={{ width: progressWidth }}
        />
      </div>

      {offlineNotice ? (
        <Notice className="mb-6" tone="info">
          {offlineNotice}
          {pendingQueue > 0 ? `（本机待同步 ${pendingQueue} 份）` : ""}
        </Notice>
      ) : null}

      {/* 双栏布局：<900px 折叠单栏（断点与原型一致，不用 Tailwind 缺省的 lg:1024px）。 */}
      <div className="grid grid-cols-1 gap-10 min-[900px]:grid-cols-[minmax(0,640px)_236px] min-[900px]:items-start min-[900px]:gap-[72px]">
        {/* 主练习区 */}
        <div className="min-w-0">
          {/* 会话条：题库下拉｜到期 chip｜本轮进度｜今天到此（本轮总结）。
              原型里它是左栏第一个子元素：640px 内右缘与题干列对齐、批注栏与之顶对齐。 */}
          <div className="mb-[26px] flex flex-wrap items-center gap-x-[18px] gap-y-2.5 border-b border-rule pb-3.5 font-mono text-[10px] tracking-[0.1em] text-ink-faint">
            {publishedBanks.length > 0 ? (
              <select
                value={effectiveBankId ?? ""}
                onChange={(event) => switchBank(event.target.value)}
                title="选择题库"
                aria-label="选择题库"
                // 样式与原型 .select + .bank-select 一致：无衬线 11px、内边距 4px 8px、只显示题库名。
                className="rounded-[2px] border border-rule bg-transparent px-2 py-1 font-sans text-[11px] tracking-[0.02em] text-ink-muted outline-none focus:border-ink-faint"
              >
                {publishedBanks.map((bank) => (
                  <option key={bank.id} value={bank.id}>
                    {bank.name}
                  </option>
                ))}
              </select>
            ) : null}
            <Link
              to="/review"
              search={{ filter: "due" }}
              className="transition-colors hover:text-ink"
            >
              <b className="mr-[3px] font-medium text-vermilion">{dueCount}</b>句到期
            </Link>
            <span>
              本轮 {round.done}
              {goal ? ` / ${goal}` : ""}
            </span>
            <button
              type="button"
              onClick={() => setSummaryOpen(true)}
              className="linkish ml-auto text-[11px]"
            >
              今天到此
            </button>
          </div>

          {/* 模式页签 + qmeta 同行 space-between（原型 .practice-head）。 */}
          <div className="flex flex-wrap items-center justify-between gap-[22px]">
            <div className="flex gap-5">
              {(
                [
                  ["CLOZE", "填空"],
                  ["FREE", "整句"],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={displayMode === value}
                  onClick={() => switchMode(value)}
                  className={cn(
                    "cursor-pointer border-b pb-[3px] text-[13px] tracking-[0.06em]",
                    displayMode === value
                      ? "border-vermilion text-ink"
                      : "border-transparent text-ink-faint",
                  )}
                >
                  {label}
                </button>
              ))}
            </div>
            {displaySentence ? (
              // 原型 .qmeta：11px mono、.12em、ink3，dot 3px 圆点。
              <div className="qmeta">
                {levelLabel(displaySentence.level) ? (
                  <span>{levelLabel(displaySentence.level)}</span>
                ) : null}
                {levelLabel(displaySentence.level) && displaySentence.tags.length > 0 ? (
                  <span className="dot" aria-hidden="true" />
                ) : null}
                <span>{displaySentence.tags.join(" · ") || "未分类"}</span>
                {qidx ? (
                  <>
                    <span className="dot" aria-hidden="true" />
                    <span>{qidx}</span>
                  </>
                ) : null}
                {effectiveBankId ? (
                  <>
                    <span className="dot" aria-hidden="true" />
                    <button type="button" onClick={() => setPeekOpen(true)} className="linkish">
                      题库
                    </button>
                  </>
                ) : null}
              </div>
            ) : null}
          </div>

          {displaySentence ? (
            <>
              {/* 原型 .prompt：clamp 字号；≤900px 下边距 32px、≤520px 字号 23px（媒体查询同参）。 */}
              <h1 className="mb-8 mt-[22px] max-[520px]:text-[23px] min-[900px]:mb-11 font-serif text-[clamp(24px,3.4vw,34px)] font-medium leading-[1.55] tracking-[0.02em] text-ink">
                <ChinesePrompt sentence={displaySentence} />
              </h1>

              {displayMode === "CLOZE" ? (
                <div>
                  {/* 原型 .skeleton：衬线、行高 2.5、≤520px 缩为 17px/2.7；字号随参考译文长度三档。 */}
                  <p
                    ref={skeletonRef}
                    className="m-0 max-[520px]:leading-[2.7] max-[520px]:text-[17px] font-serif leading-[2.5] text-ink"
                    style={{ fontSize: skeletonFontSize }}
                  >
                    {displaySentence.chunks
                      .slice()
                      .sort((a, b) => a.index - b.index)
                      .map((chunk, position, all) => {
                        // 语块之间用真实空格文本节点分隔（原型同款），末尾不留空格。
                        const trailing = position < all.length - 1 ? " " : null;
                        const blankIndex = displaySentence.blank.indexOf(chunk.index);
                        if (blankIndex < 0) {
                          return (
                            <Fragment key={chunk.index}>
                              {chunk.answers[0]}
                              {trailing}
                            </Fragment>
                          );
                        }
                        const value = submittedSlots
                          ? (submittedSlots[chunk.index] ?? "")
                          : (answers[chunk.index] ?? "");
                        const annotation = submittedSlots
                          ? (attempt?.result.annotations.find(
                              (item) => item.chunkIndex === chunk.index,
                            ) ?? null)
                          : null;
                        // 原型按规范答案逐词开框：每个词一个下划线输入框，宽度随词长 ch 自适应；
                        // 答案仍以整块字符串存取（草稿/提交契约不变），此处按位置拆词回填。
                        const canonical = chunk.answers[0] ?? "";
                        const expectedWords =
                          canonical.trim().length > 0
                            ? canonical.split(/\s+/).filter(Boolean)
                            : [""];
                        const typedWords = value.trim() === "" ? [] : value.split(/\s+/);
                        // 与规范答案词数相同的可接受答案，按位置取词作为逐词判定的备选（原型 alts 语义）。
                        const altWords = expectedWords.map((_, k) =>
                          chunk.answers
                            .map((answer) => answer.split(/\s+/).filter(Boolean))
                            .filter((words) => words.length === expectedWords.length)
                            .map((words) => words[k])
                            .filter(Boolean),
                        );
                        // 整块命中或看答案（无批注）时全部词标 moss，等价原型 full 分支的提前返回。
                        const allHit =
                          submittedSlots !== null && (annotation === null || annotation.correct);
                        return (
                          <Fragment key={chunk.index}>
                            <span className="relative inline-flex items-baseline gap-px whitespace-nowrap">
                              <span className={BLANK_BADGE}>{blankIndex + 1}</span>
                              {expectedWords.map((word, k) => {
                                const typed = typedWords[k] ?? "";
                                const hit =
                                  allHit ||
                                  (typed !== "" &&
                                    (normalizeAnswer(typed) === normalizeAnswer(word) ||
                                      altWords[k].some(
                                        (alt) => normalizeAnswer(alt) === normalizeAnswer(typed),
                                      )));
                                return (
                                  // biome-ignore lint/suspicious/noArrayIndexKey: 语块内词序固定且词面可能重复（如 more and more），位置即稳定标识。
                                  <Fragment key={`slot-${chunk.index}-${k + 1}`}>
                                    <input
                                      value={typed}
                                      onChange={(event) => {
                                        // 空位保留为空串参与拼接，保证词与框的位置关系稳定。
                                        const next = expectedWords
                                          .map((_, i) =>
                                            i === k ? event.target.value : (typedWords[i] ?? ""),
                                          )
                                          .join(" ");
                                        setAnswers((current) => ({
                                          ...current,
                                          [chunk.index]: next,
                                        }));
                                      }}
                                      onKeyDown={(event) =>
                                        handleSlotKeyDown(event, event.currentTarget)
                                      }
                                      readOnly={Boolean(submittedSlots)}
                                      aria-label={`第 ${blankIndex + 1} 空第 ${k + 1} 词：${chunk.zh}`}
                                      autoComplete="off"
                                      autoCapitalize="none"
                                      spellCheck={false}
                                      enterKeyHint={
                                        blankIndex < displaySentence.blank.length - 1
                                          ? "next"
                                          : "send"
                                      }
                                      className={cn(
                                        // 原型 .skeleton input：只有下边线、内边距 0 4px 1px、左右 1px 外距、focus 朱红。
                                        "mx-px border-b bg-transparent px-1 pb-px font-serif outline-none transition-[border-color] duration-150 ease-[ease]",
                                        submittedSlots
                                          ? hit
                                            ? "border-moss text-moss"
                                            : "border-vermilion text-ink-faint line-through decoration-1"
                                          : "border-ink/32 text-ink focus:border-vermilion",
                                      )}
                                      style={{
                                        width: `${Math.max(5, word.length + 1, typed.length + 1)}ch`,
                                      }}
                                    />
                                    {k < expectedWords.length - 1 ? (
                                      <span className="inline-block w-[.35ch]" aria-hidden="true">
                                        {" "}
                                      </span>
                                    ) : null}
                                  </Fragment>
                                );
                              })}
                              {annotation && !annotation.correct && annotation.minimalFix ? (
                                <span className="ml-[7px] font-sans text-[0.7em] tracking-[0.01em] text-vermilion">
                                  {annotation.minimalFix}
                                </span>
                              ) : null}
                            </span>
                            {trailing}
                          </Fragment>
                        );
                      })}
                    {/* 原型在骨架末尾直接追加参考译文的句末标点（语块答案不含标点）。 */}
                    {/[.!?]$/.test(displaySentence.ref) ? displaySentence.ref.slice(-1) : null}
                  </p>

                  <div className="keyboard-safe mt-9 flex flex-wrap items-center gap-3.5">
                    {/* 判分后整行保留（与原型 finish 的 DOM 稳定处理一致）：提交禁用、
                        首字母可点、看答案空转——避免结果出现时操作行整行消失导致跳版。 */}
                    <button
                      type="button"
                      onClick={() => handleSubmit()}
                      disabled={Boolean(attempt)}
                      className="btn primary"
                    >
                      提交 <kbd>⌘↵</kbd>
                    </button>
                    <button
                      type="button"
                      onClick={cycleHint}
                      disabled={hintLevel >= 3}
                      className="btn ghost"
                    >
                      首字母
                    </button>
                    {/* 有意偏差（比原型更稳）：原型单击「看答案」直接出答案；
                        这里保留二次确认——看答案会把题目记入学习中队列且不可撤销（错题规则 4「改对才过」），
                        误触代价高于原型演示，确认步给用户一次反悔机会。 */}
                    {revealConfirm && !attempt ? (
                      <>
                        <span className="text-xs text-ink-muted">
                          看答案会记为学习中，不计入正确率。
                        </span>
                        <Button
                          size="sm"
                          variant="danger"
                          disabled={submitMutation.isPending || offlineNotice !== null}
                          onClick={() => submitFollowUp("REVEAL")}
                        >
                          确认看答案
                        </Button>
                        <Button size="sm" variant="quiet" onClick={() => setRevealConfirm(false)}>
                          再想想
                        </Button>
                      </>
                    ) : (
                      // 判分后（attempt 存在）骨架已只读：按钮保留可点但空转，
                      // 与原型 revealCloze 的 graded 守卫（if (state.graded) return）同款。
                      <button
                        type="button"
                        onClick={() => {
                          if (!attempt) {
                            setRevealConfirm(true);
                          }
                        }}
                        aria-label="看答案（需二次确认；看答案会把本题记为学习中）"
                        className="btn ghost"
                      >
                        看答案
                      </button>
                    )}
                    {hintLine ? <span className="hintline">{hintLine}</span> : null}
                  </div>
                </div>
              ) : (
                <div>
                  <label htmlFor="free-translation" className="sr-only">
                    英文译文
                  </label>
                  {/* 原型 .freeinput：只有下边线、衬线 20px/1.9、placeholder 斜体。 */}
                  <Textarea
                    id="free-translation"
                    value={submittedFree ?? freeText}
                    onChange={(event) => setFreeText(event.target.value)}
                    readOnly={submittedFree !== null}
                    rows={5}
                    placeholder="写下你的英文译文…"
                    spellCheck
                    className="h-[132px] resize-none rounded-none border-x-0 border-t-0 border-b border-rule bg-transparent px-0 py-1 font-serif text-[20px] leading-[1.9] text-ink placeholder:italic focus:border-vermilion focus:ring-0"
                  />
                  <div className="keyboard-safe mt-9 flex flex-wrap items-center gap-3.5">
                    {/* 判分后提交键保留但禁用（与原型 finish 后 DOM 稳定、#submit.disabled 一致）。 */}
                    <button
                      type="button"
                      onClick={() => handleSubmit()}
                      disabled={Boolean(attempt)}
                      className="btn primary"
                    >
                      提交 <kbd>⌘↵</kbd>
                    </button>
                    <span className="font-mono text-[11px] tracking-[0.08em] text-ink-faint">
                      {(submittedFree ?? freeText).trim() === ""
                        ? 0
                        : (submittedFree ?? freeText).trim().split(/\s+/).length}{" "}
                      words
                    </span>
                  </div>
                </div>
              )}
            </>
          ) : (
            <EmptyState
              className="mt-6"
              title="题目没有加载出来"
              description="可以重试，或回到今日重新开始。"
              action={
                <Button variant="ghost" onClick={() => void nextQuery.refetch()}>
                  重试
                </Button>
              }
            />
          )}

          {submitError ? (
            <Notice className="mt-6" tone="danger">
              提交未确认：{submitError}
              <span className="mt-2 block text-xs">
                答案还在本机，不会丢。可以重试提交，或返回修改。
              </span>
              {pendingSubmission ? (
                <div className="mt-3 flex gap-3">
                  <Button
                    size="sm"
                    disabled={submitMutation.isPending}
                    onClick={() => submitMutation.mutate(pendingSubmission)}
                  >
                    重试提交
                  </Button>
                  <Button size="sm" variant="quiet" onClick={backToEdit}>
                    返回修改
                  </Button>
                </div>
              ) : null}
            </Notice>
          ) : null}

          {attempt ? (
            <section
              ref={resultRef}
              tabIndex={-1}
              className="rise mt-10 outline-none"
              role="status"
              aria-live="polite"
            >
              <div className="flex flex-wrap items-baseline gap-3 border-b border-rule pb-[22px]">
                <p className="font-serif text-[44px] leading-none tracking-[-0.01em] text-ink">
                  {displayScore}
                  <span className="ml-1 font-mono text-[13px] text-ink-faint">/100</span>
                </p>
                <p className="text-[13px] tracking-[0.04em] text-ink-muted">{displayNote}</p>
                {attempt.replayed ? (
                  <span className="font-mono text-[10px] tracking-[0.08em] text-ink-faint">
                    幂等重放
                  </span>
                ) : null}
              </div>

              <div className="mt-[26px]">
                <p className="label">参考译文</p>
                <p className="mt-2.5 font-serif text-[19px] leading-[1.75] text-ink">
                  {attempt.reference.ref}
                </p>
                <div className="mt-3 flex gap-4">
                  <button
                    type="button"
                    onClick={() => speakRef(attempt.reference.ref)}
                    className="linkish"
                  >
                    朗读
                  </button>
                  {/* 音标只在句子携带 ipa 时出现（当前仅 mock 演示数据有），默认收起，点按切换。 */}
                  {displaySentence?.ipa ? (
                    <button
                      type="button"
                      onClick={() => setIpaVisible((visible) => !visible)}
                      aria-expanded={ipaVisible}
                      className="linkish"
                    >
                      音标
                    </button>
                  ) : null}
                </div>
                {displaySentence?.ipa && ipaVisible ? (
                  <p className="mt-2 font-mono text-[13px] leading-[1.8] tracking-[0.02em] text-ink-muted">
                    {displaySentence.ipa}
                  </p>
                ) : null}
              </div>

              {attempt.reference.note ? (
                <div className="mt-[18px] border-t border-rule pt-4">
                  <p className="label mb-2">解析</p>
                  <p className="m-0 text-[13px] leading-[1.8] text-ink-muted">
                    {attempt.reference.note}
                  </p>
                </div>
              ) : null}

              {attempt.nextAction.retryRequired ? (
                <section className="mt-[26px] border-t border-rule pt-[22px]">
                  <p className="label">改对才过</p>
                  <p className="mt-2 mb-0 text-xs leading-[1.7] text-ink-muted">
                    把参考译文重打一遍，确认自己真的会了。错题明天还会出现。
                  </p>
                  <input
                    value={retryText}
                    onChange={(event) => {
                      setRetryText(event.target.value);
                      setRetryError(null);
                    }}
                    onKeyDown={(event) => {
                      if (event.nativeEvent.isComposing || event.keyCode === 229) {
                        return;
                      }
                      if (event.key === "Enter") {
                        event.preventDefault();
                        submitFollowUp("RETRY");
                      }
                    }}
                    placeholder="重打参考译文…"
                    autoComplete="off"
                    spellCheck={false}
                    aria-label="重打参考译文"
                    className="mt-3.5 w-full border-b border-rule bg-transparent py-[9px] font-serif text-[17px] text-ink outline-none focus:border-vermilion"
                  />
                  {retryError ? (
                    <p role="alert" className="mt-2 text-sm text-vermilion-deep">
                      {retryError}
                    </p>
                  ) : null}
                  <div className="keyboard-safe mt-3.5 flex flex-wrap items-center gap-3.5">
                    <button
                      type="button"
                      disabled={submitMutation.isPending || offlineNotice !== null}
                      onClick={() => submitFollowUp("RETRY")}
                      className="btn ghost"
                    >
                      核对
                    </button>
                    {retryFixed ? (
                      // 原型 checkRetry：修对后在按钮行显示苔绿「已修复」，块与输入不隐藏。
                      <span className="hintline text-moss">已修复</span>
                    ) : null}
                    {revealConfirm ? (
                      <>
                        <span className="text-xs text-ink-muted">
                          看答案会记为学习中，不计入正确率。
                        </span>
                        <Button
                          size="sm"
                          variant="danger"
                          disabled={submitMutation.isPending || offlineNotice !== null}
                          onClick={() => submitFollowUp("REVEAL")}
                        >
                          确认看答案
                        </Button>
                        <Button size="sm" variant="quiet" onClick={() => setRevealConfirm(false)}>
                          再想想
                        </Button>
                      </>
                    ) : (
                      // 同上：二次确认是有意偏差（原型单击直接看答案），理由见提交区同款注释。
                      <button
                        type="button"
                        onClick={() => setRevealConfirm(true)}
                        aria-label="看答案并记为学习中（需二次确认）"
                        className="linkish text-[11px]"
                      >
                        看答案，记为学习中
                      </button>
                    )}
                  </div>
                </section>
              ) : null}

              {drillTagLabel ? (
                <div className="mt-[22px] flex flex-wrap items-center gap-3 text-xs text-ink-muted">
                  <span>下一步：再练 2 句「{drillTagLabel}」</span>
                  <button type="button" onClick={() => advance(drillTagLabel)} className="linkish">
                    开始
                  </button>
                </div>
              ) : null}

              {reviewedScore !== null && attempt.score !== null ? (
                <p className="mt-3.5 text-xs leading-[1.7] text-vermilion">
                  复核 +{reviewedScore - attempt.score} 分 · 演示占位，未写入记录，不覆盖原判分。
                  <span className="ml-1 text-ink-faint">（M3 接入真实模型后按追加记录实现）</span>
                </p>
              ) : null}

              {/* 原型 .actions：上边距 36px、间距 14px，无上边框。 */}
              <div className="keyboard-safe mt-9 flex flex-wrap items-center gap-3.5">
                {!isReveal && score !== null && score < 100 && reviewedScore === null ? (
                  <button type="button" onClick={handleModelReview} className="linkish">
                    让模型复核
                  </button>
                ) : null}
                <button
                  type="button"
                  onClick={() => advance()}
                  disabled={submitMutation.isPending}
                  aria-disabled={!advancable}
                  aria-busy={submitMutation.isPending}
                  className={cn("btn primary", !advancable && "cursor-not-allowed opacity-40")}
                >
                  下一句 <kbd>⌘↵</kbd>
                </button>
              </div>
            </section>
          ) : null}
        </div>

        {/* 批注栏：<900px 折叠到内容下方 */}
        <aside className="border-t border-rule pt-[22px] min-[900px]:min-h-[240px] min-[900px]:border-l min-[900px]:border-t-0 min-[900px]:pl-7 min-[900px]:pt-0">
          <h2 className="mb-5 font-mono text-[10px] tracking-[0.18em] text-ink-faint">批注</h2>
          <ol className="m-0 list-none p-0">
            {!attempt ? (
              <li className="text-xs text-ink-faint">提交后，这里会出现批注。</li>
            ) : isReveal ? (
              <li className="text-xs text-ink-faint">看答案不计入批注。</li>
            ) : attempt.result.annotations.length === 0 ? (
              <li className="text-xs text-ink-faint">全对，没有批注。</li>
            ) : (
              attempt.result.annotations.map((annotation, index) => {
                const zh = annotation.key.startsWith("free:")
                  ? null
                  : (displaySentence?.chunks.find((item) => item.index === annotation.chunkIndex)
                      ?.zh ?? null);
                // free 批注的 chunkIndex 是占位 0，不指向真实语块，语块注只对填空批注生效。
                const chunkNote = annotation.key.startsWith("free:")
                  ? null
                  : (displaySentence?.chunks.find((item) => item.index === annotation.chunkIndex)
                      ?.note ?? null);
                return (
                  <li
                    key={annotation.key}
                    className="relative mb-[18px] pl-[22px] text-[12.5px] leading-[1.8] text-ink-muted"
                  >
                    <b className="absolute left-0 top-[1px] font-mono text-[10px] font-medium text-vermilion">
                      {index + 1}
                    </b>
                    {zh ? (
                      <>
                        <em className="font-serif text-[13.5px] not-italic text-ink">{zh}</em>
                        {/* 原型批注条目：语块中文后跟「 · 」再接考点徽标。 */}
                        {" · "}
                      </>
                    ) : null}
                    <span className="mr-1.5 inline-block rounded-[2px] border border-rule px-[5px] py-px font-mono text-[9px] tracking-[0.08em] text-ink-faint">
                      {annotation.tag}
                    </span>
                    {!annotation.correct ? (
                      // 原型批注正文（renderNotes）：语块教学注优先，缺省回退「正确写法」；
                      // received/minimalFix 已由骨架删除线与行内 .fix 呈现，不再重复成块展示。
                      <span>
                        {chunkNote ||
                          (annotation.expected.length > 0 ? (
                            <>
                              正确写法：
                              <em className="font-serif not-italic">{annotation.expected[0]}</em>
                            </>
                          ) : null)}
                      </span>
                    ) : annotation.message ? (
                      <span>{annotation.message}</span>
                    ) : null}
                    {!annotation.correct ? (
                      <AnnotationActions
                        attemptId={attempt.attemptId}
                        annotation={annotation}
                        disabled={disabledActions}
                      />
                    ) : null}
                  </li>
                );
              })
            )}
          </ol>
        </aside>
      </div>

      <RoundSummaryModal
        open={summaryOpen}
        round={round}
        dueCount={dueCount}
        onClose={() => setSummaryOpen(false)}
        onHome={() => {
          setSummaryOpen(false);
          void navigate({ to: "/today" });
        }}
        onAgain={() => {
          setSummaryOpen(false);
          advance();
        }}
      />

      <BankPeekDrawer
        open={peekOpen}
        bankId={peekBank?.id ?? null}
        bankName={peekBank?.name ?? null}
        bankMeta={
          peekBank
            ? `${peekSentences.length} 句${peekBank.description ? ` · ${peekBank.description}` : ""}`
            : null
        }
        sentences={peekSentences}
        currentSentenceId={displaySentence?.id ?? null}
        queuedIds={queuedIds}
        sentencesLoading={bankSentencesQuery.isPending}
        onJump={peekJump}
        onClose={closePeek}
      />

      {toastMessage ? (
        // 外层负责水平定位（translateX），内层承载 rise 动画：原型 toast 从 translate(-50%,12px)
        // 升到位，若动画直接压在同一个节点上会覆盖 -translate-x-1/2 导致跳位。
        <div className="fixed bottom-8 left-1/2 z-[60] -translate-x-1/2">
          <div
            role="status"
            className="rise rounded-[2px] bg-ink px-4 py-[9px] text-[12.5px] text-paper"
          >
            {toastMessage}
          </div>
        </div>
      ) : null}
    </div>
  );
}
