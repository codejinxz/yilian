/**
 * mock 演示数据库：所有账号密码与邀请码仅是本地演示数据（受 VITE_USE_MOCK 门控），
 * 禁止复用为任何真实环境的凭据。
 */
import { gradeCloze } from "@/features/practice/grading";
import type { AdminUser, AuditLog, ErrorLogDetail } from "@/lib/schemas/admin";
import type { BankView, ImportBatchView, ImportRow } from "@/lib/schemas/content";
import type {
  AttemptRequest,
  AttemptResponse,
  GradingResult,
  PracticeSentence,
  Review,
  UserSettings,
} from "@/lib/schemas/practice";

const STORAGE_KEY = "yilian.mock.db.v1";
const DAY_MS = 86_400_000;

/** mock 用户只多存密码与设置；API 响应会裁掉这两项。 */
export interface MockUser extends AdminUser {
  password: string;
  settings: UserSettings;
}

/** mock 句子比 API 多一个 bankId，便于题库筛选；返回给页面时会裁掉。 */
export interface MockSentence extends PracticeSentence {
  bankId: string;
}

/** mock 复习卡比 API 多挂 userId，便于按当前会话过滤；字段本身与复习投影一致。 */
export interface MockReview extends Review {
  userId: string;
}

/** mock 答题记录：request/response 成对保存，练习回看与统计重算都直接取用，不必反序列化推导。 */
export interface MockAttempt {
  userId: string;
  request: AttemptRequest;
  response: AttemptResponse;
  createdAt: string;
  /** 原始中文，统计页最近记录使用，不进入 API 响应。 */
  zh: string;
}

/** mock 申诉/异议：对应批注上的「不认同」入口，状态机与申诉流转约定保持一致。 */
export interface MockAppeal {
  id: string;
  attemptId: string;
  annotationKey: string;
  kind: "APPEAL" | "DISPUTE";
  status: "PENDING" | "ACCEPTED" | "REJECTED" | "WITHDRAWN";
  proposedAnswer: string | null;
  reason: string | null;
}

/** mock 导入批次：行级明细留在内存以便预览→提交两段式演示，API 响应只暴露汇总字段。 */
export interface MockImportBatch {
  batchId: string;
  bankId: string;
  format: "JSON" | "CSV";
  originalFilename: string;
  sizeBytes: number;
  status: ImportBatchView["status"];
  totalRows: number;
  validRows: number;
  invalidRows: number;
  importedRows: number;
  createdAt: string;
  previewedAt: string | null;
  committedAt: string | null;
  rolledBackAt: string | null;
  errorSummary: ImportBatchView["errorSummary"];
  rows: ImportRow[];
  /** 预览时的原始行；仅 mock 内部用于 commit，不进入 API 响应。 */
  rawRows: Record<string, unknown>;
}

/** mock 邀请码：内测期间凭码注册，一码一人；与注册端点的校验共用。 */
export interface MockInvite {
  code: string;
  used: boolean;
  usedBy: string | null;
  createdAt: string;
}

/**
 * 管理端「上传 / 导入」批次：对应 lib/api/upload.ts 的 UploadBatch 快照。
 * 与导入批次（分块校验→提交）不同，这是文件直传后台处理的演示状态机。
 */
export interface MockUploadBatch {
  batchId: string;
  originalFilename: string;
  // TODO(mock): 演示数据，真实接口落地后删除——上传时选择的导入目标题库，回显给批次查询。
  bankId: string | null;
  sizeBytes: number;
  status: "UPLOADED" | "PROCESSING" | "PAUSED" | "COMPLETED" | "FAILED";
  totalRows: number;
  processedRows: number;
  successRows: number;
  failedRows: number;
  errorMessage: string | null;
  createdAt: string;
}

/** mock 数据库根对象：整体持久化到 localStorage 并带版本号，结构变更时靠 version 识别并重置。 */
export interface MockDb {
  version: 1;
  sessionUserId: string | null;
  users: MockUser[];
  banks: BankView[];
  sentences: MockSentence[];
  reviews: MockReview[];
  attempts: MockAttempt[];
  appeals: MockAppeal[];
  auditLogs: AuditLog[];
  errorLogs: ErrorLogDetail[];
  imports: MockImportBatch[];
  /** 上传批次；历史存储可能缺少该字段，读取时补空数组。 */
  uploads: MockUploadBatch[];
  /** 邀请码；历史存储可能缺少该字段，读取时补默认码。 */
  invites: MockInvite[];
  seq: number;
  inviteUsedBy: string | null;
}

/**
 * 生成相对当前时间的 UTC ISO 字符串，保证 mock 演示数据始终有到期/今日记录。
 * @param offsetDays 相对今天的天数偏移
 * @param offsetHours 额外小时偏移
 * @returns ISO 时间
 */
export function isoAt(offsetDays = 0, offsetHours = 0): string {
  return new Date(Date.now() + offsetDays * DAY_MS + offsetHours * 3_600_000).toISOString();
}

/**
 * 生成稳定但可读的 mock ULID（26 位大写字母数字）。
 * @param seed 序号
 * @returns 26 位假 ULID
 */
export function mockUlid(seed: number): string {
  return `01J${String(seed).padStart(3, "0")}Z6K2M8Q4R7T1V3W5X`;
}

function makeSentence(
  id: string,
  bankId: string,
  version: number,
  sentenceVersionId: string,
  questionVersion: number,
  zh: string,
  ref: string,
  note: string,
  chunks: PracticeSentence["chunks"],
  blank: number[],
  tags: string[],
  level: string,
  ipa?: string,
): MockSentence {
  return {
    id,
    bankId,
    version,
    sentenceVersionId,
    questionVersion,
    status: "PUBLISHED",
    deleted: false,
    zh,
    ref,
    note,
    chunks,
    blank,
    tags,
    level,
    // ipa 只在种子显式给出时携带；真实服务端暂不返回该字段，练习页按「有才渲染」处理。
    ...(ipa ? { ipa } : {}),
  };
}

function chunks(
  ...items: Array<[string, string, string, string[]?, string?]>
): PracticeSentence["chunks"] {
  return items.map(([zh, answer, tag, alternatives = [], note], index) => ({
    index,
    zh,
    answers: [answer, ...alternatives],
    tag,
    note: note ?? null,
  }));
}

/** 种子句子覆盖全对、部分对、全错、申诉与已掌握等场景。 */
function seedSentences(): MockSentence[] {
  return [
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A0",
      "1",
      12,
      "1001",
      3,
      "他越来越重视这件事。",
      "He is paying more and more attention to this.",
      "「越来越」可用 more and more 或更书面的 increasingly；「重视」后接 attention 时用 pay attention to，不能加 s。",
      chunks(
        ["他", "He", "代词"],
        ["越来越", "more and more", "副词结构", ["increasingly"], "increasingly 更书面"],
        [
          "重视",
          "pay attention to",
          "搭配",
          ["attach importance to"],
          "attach importance to 更正式",
        ],
        ["这件事", "this", "代词", ["this matter"]],
      ),
      [1, 2],
      ["副词结构", "搭配"],
      "TEM8",
      // 通用美式音标（General American）；与原型一致只作演示，真实数据由内容侧提供。
      "/hi ɪz ˈpeɪɪŋ mɔːr ænd mɔːr əˈtɛnʃən tuː ðɪs/",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A1",
      "1",
      12,
      "1002",
      2,
      "这项政策对中小企业影响最大。",
      "This policy has the greatest impact on small and medium-sized enterprises.",
      "「影响最大」用 have the greatest impact on；small and medium-sized enterprises 是固定说法，medium 用单数。",
      chunks(
        ["这项政策", "This policy", "名词短语"],
        ["影响最大", "has the greatest impact", "搭配"],
        [
          "对中小企业",
          "on small and medium-sized enterprises",
          "介词结构",
          ["on SMEs"],
          "口语可用 SMEs，正式写作建议写全",
        ],
      ),
      [1, 2],
      ["搭配", "介词结构"],
      "TEM8",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A2",
      "1",
      12,
      "1003",
      2,
      "我们应当把有限的时间用在最重要的事情上。",
      "We should spend our limited time on the most important things.",
      "spend time on something 的介词是 on；「最重要」用 the most important，注意定冠词。",
      chunks(
        ["我们", "We", "代词"],
        ["应当花费", "should spend", "情态动词"],
        ["有限的时间", "our limited time", "名词短语"],
        ["在最重要的事情上", "on the most important things", "介词结构"],
      ),
      [1, 3],
      ["情态动词", "介词结构"],
      "CATTI",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A3",
      "1",
      12,
      "1004",
      4,
      "尽管困难重重，她仍然坚持自己的观点。",
      "Despite the difficulties, she still stuck to her own point of view.",
      "despite 后接名词，不接句子；stick to 的过去式是 stuck，不要写成 sticked。",
      chunks(
        ["尽管困难重重", "Despite the difficulties", "让步结构"],
        ["她仍然", "she still", "副词"],
        ["坚持", "stuck to", "动词短语"],
        ["自己的观点", "her own point of view", "名词短语"],
      ),
      [0, 2],
      ["让步结构", "动词短语"],
      "TEM8",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A4",
      "1",
      12,
      "1005",
      1,
      "这本书值得一读。",
      "This book is worth reading.",
      "be worth doing 是固定搭配，worth 后接动名词，不接不定式。",
      chunks(
        ["这本书", "This book", "名词短语"],
        ["值得", "is worth", "搭配"],
        ["一读", "reading", "非谓语"],
      ),
      [1, 2],
      ["搭配", "非谓语"],
      "CET6",
      "/ðɪs bʊk ɪz wɜːrθ ˈriːdɪŋ/",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A5",
      "1",
      12,
      "1006",
      2,
      "会议被推迟到下周三。",
      "The meeting has been postponed until next Wednesday.",
      "现在完成时的被动语态是 has been postponed；推迟到某时用 until，不用 to。",
      chunks(
        ["会议", "The meeting", "名词短语"],
        ["被推迟", "has been postponed", "被动语态"],
        ["到下周三", "until next Wednesday", "介词结构"],
      ),
      [1, 2],
      ["被动语态", "介词结构"],
      "CET6",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A6",
      "1",
      12,
      "1007",
      1,
      "他因为迟到而道歉。",
      "He apologized for being late.",
      "apologize for doing 是固定搭配；for 后接动名词 being，不接不定式。",
      chunks(
        ["他", "He", "代词"],
        ["道歉", "apologized", "动词搭配"],
        ["因为迟到", "for being late", "动名词"],
      ),
      [1, 2],
      ["动词搭配", "动名词"],
      "CET6",
      "/hi əˈpɑːlədʒaɪzd fər ˈbiːɪŋ leɪt/",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A7",
      "1",
      12,
      "1008",
      2,
      "越来越多的人开始关注环境保护。",
      "More and more people are beginning to pay attention to environmental protection.",
      "「越来越多」用 more and more；begin to do 与 begin doing 均可，但本句用不定式更自然。",
      chunks(
        ["越来越多的人", "More and more people", "副词结构"],
        ["开始", "are beginning", "动词搭配"],
        ["关注", "to pay attention to", "搭配"],
        ["环境保护", "environmental protection", "名词短语"],
      ),
      [1, 2, 3],
      ["副词结构", "搭配", "名词短语"],
      "TEM8",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A8",
      "1",
      12,
      "1009",
      3,
      "这个问题比我们预想的复杂得多。",
      "This problem is much more complicated than we expected.",
      "比较级前用 much 修饰；than 后接 we expected，保持主谓结构。",
      chunks(
        ["这个问题", "This problem", "名词短语"],
        ["复杂得多", "is much more complicated", "比较结构"],
        ["比我们预想的", "than we expected", "比较结构"],
      ),
      [1, 2],
      ["比较结构", "形容词"],
      "TEM8",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A9",
      "1",
      12,
      "1010",
      2,
      "只有通过不断练习，才能提高翻译水平。",
      "Only through constant practice can you improve your translation skills.",
      "only + 状语置于句首时主句部分倒装，can 提到主语前；practice 在此不可数。",
      chunks(
        ["只有通过不断练习", "Only through constant practice", "倒装句"],
        ["才能提高", "can you improve", "倒装句"],
        ["翻译水平", "your translation skills", "名词短语"],
      ),
      [0, 1],
      ["倒装句", "名词短语"],
      "CATTI",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9B0",
      "1",
      12,
      "1011",
      1,
      "他提出的建议得到了广泛支持。",
      "The suggestion he put forward received widespread support.",
      "put forward 是「提出」的固定短语；先行词 suggestion 后接省略 that 的定语从句。",
      chunks(
        ["建议", "The suggestion", "名词短语"],
        ["他提出的", "he put forward", "定语从句"],
        ["得到了", "received", "动词搭配"],
        ["广泛支持", "widespread support", "名词短语"],
      ),
      [1, 2, 3],
      ["定语从句", "动词搭配"],
      "TEM8",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9B1",
      "1",
      12,
      "1012",
      2,
      "这座城市以其悠久的历史而闻名。",
      "This city is famous for its long history.",
      "be famous for 表示「因……而闻名」，不要与 be famous as 混淆。",
      chunks(
        ["这座城市", "This city", "名词短语"],
        ["以……而闻名", "is famous for", "形容词搭配"],
        ["其悠久的历史", "its long history", "名词短语"],
      ),
      [1, 2],
      ["形容词搭配", "介词结构"],
      "CET6",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9B2",
      "2",
      3,
      "2001",
      1,
      "写作时，清晰比华丽更重要。",
      "In writing, clarity matters more than elegance.",
      "matter 作动词表示「重要」；比较结构 more than 前后保持名词对称。",
      chunks(
        ["写作时", "In writing", "介词结构"],
        ["清晰", "clarity", "名词短语"],
        ["比华丽更重要", "matters more than elegance", "比较结构"],
      ),
      [1, 2],
      ["比较结构", "名词短语"],
      "IELTS",
    ),
    makeSentence(
      "01J9Z6K2M8Q4R7T1V3W5X7Y9B3",
      "2",
      3,
      "2002",
      1,
      "政府应鼓励企业减少一次性塑料。",
      "The government should encourage businesses to reduce single-use plastic.",
      "encourage somebody to do 是固定搭配；single-use 作定语时保留连字符。",
      chunks(
        ["政府", "The government", "名词短语"],
        ["应鼓励企业", "should encourage businesses", "动词搭配"],
        ["减少一次性塑料", "to reduce single-use plastic", "不定式"],
      ),
      [1, 2],
      ["动词搭配", "不定式"],
      "IELTS",
    ),
  ];
}

function seedUsers(): MockUser[] {
  const baseSettings: UserSettings = {
    timezone: "Asia/Shanghai",
    dailyGoal: 3,
    ratingStandard: "TEM8",
    desiredRetention: 0.9,
    theme: "paper",
    notificationsEnabled: false,
  };
  const createdAt = isoAt(-120);
  return [
    {
      // 游客演示身份：未登录时 requireUser 回退到它，让游客可刷全部练习页；
      // 登录/注册切换 sessionUserId，登出回到游客态。密码仅占位，不代表可登录凭据。
      id: "guest",
      username: "游客",
      email: null,
      role: "USER",
      enabled: true,
      deleted: false,
      createdAt,
      lastLoginAt: null,
      disabledReason: null,
      attemptsCount: 0, // TODO(mock): 演示数据，真实接口落地后删除
      password: "guest-local-demo-only",
      settings: { ...baseSettings },
    },
    {
      id: "1",
      username: "admin",
      email: "admin@example.com",
      role: "ADMIN",
      enabled: true,
      deleted: false,
      createdAt,
      lastLoginAt: isoAt(0, -2),
      disabledReason: null,
      attemptsCount: 1, // TODO(mock): 演示数据，真实接口落地后删除
      password: "change-me-now",
      settings: { ...baseSettings, theme: "paper" },
    },
    {
      id: "2",
      username: "learner",
      email: "learner@example.com",
      role: "USER",
      enabled: true,
      deleted: false,
      createdAt: isoAt(-90),
      lastLoginAt: isoAt(0, -5),
      disabledReason: null,
      attemptsCount: 16, // TODO(mock): 演示数据，真实接口落地后删除
      password: "learner123",
      settings: { ...baseSettings },
    },
    {
      id: "3",
      username: "quietuser",
      email: "quiet@example.com",
      role: "USER",
      enabled: false,
      deleted: false,
      createdAt: isoAt(-70),
      lastLoginAt: isoAt(-10),
      disabledReason: "用户申请暂停账号",
      attemptsCount: 2, // TODO(mock): 演示数据，真实接口落地后删除
      password: "password123",
      settings: { ...baseSettings, dailyGoal: 1 },
    },
    {
      id: "4",
      username: "translator",
      email: "translator@example.com",
      role: "USER",
      enabled: true,
      deleted: false,
      createdAt: isoAt(-60),
      lastLoginAt: isoAt(-1),
      disabledReason: null,
      attemptsCount: 3, // TODO(mock): 演示数据，真实接口落地后删除
      password: "password123",
      settings: { ...baseSettings, dailyGoal: 5, ratingStandard: "CATTI", desiredRetention: 0.92 },
    },
    {
      id: "5",
      username: "newbie",
      email: "newbie@example.com",
      role: "USER",
      enabled: true,
      deleted: true,
      createdAt: isoAt(-45),
      lastLoginAt: isoAt(-30),
      disabledReason: null,
      attemptsCount: 0, // TODO(mock): 演示数据，真实接口落地后删除
      password: "password123",
      settings: { ...baseSettings },
    },
  ];
}

function seedBanks(): BankView[] {
  return [
    {
      id: "1",
      ownerId: null,
      name: "官方题库 · TEM8 翻译",
      description: "专八翻译语块，覆盖搭配、倒装与比较结构。",
      visibility: "PUBLIC",
      status: "PUBLISHED",
      version: 12,
      sentenceCount: 12,
    },
    {
      id: "2",
      ownerId: "2",
      name: "写作常用句",
      description: "议论文常用表达，适合雅思写作。",
      visibility: "PRIVATE",
      status: "PUBLISHED",
      version: 3,
      sentenceCount: 2,
    },
  ];
}

function makeReview(
  id: string,
  sentence: MockSentence,
  state: Review["state"],
  dueAt: string,
  overrides: Partial<MockReview> = {},
): MockReview {
  return {
    id,
    sentenceId: sentence.id,
    mode: "CLOZE",
    state,
    fsrsState: state === "MASTERED" ? "REVIEW" : state === "NEW" ? "NEW" : "LEARNING",
    dueAt,
    stability: state === "MASTERED" ? 40 : state === "NEW" ? 0 : 3.2,
    difficulty: state === "NEW" ? 0 : 5.1,
    reps: state === "NEW" ? 0 : state === "MASTERED" ? 6 : 2,
    lapses: state === "LEARNING" ? 1 : 0,
    scheduledDays: state === "MASTERED" ? 20 : state === "NEW" ? 0 : 1,
    lastRating: state === "NEW" ? null : 2,
    lastReviewedAt: state === "NEW" ? null : isoAt(-2),
    userId: "2",
    ...overrides,
  };
}

function revealResult(): GradingResult {
  return {
    schemaVersion: 1,
    score: null,
    maxScore: 100,
    hitCount: 0,
    totalCount: 0,
    annotations: [],
    summary: "已看答案，记为学习中",
  };
}

/**
 * 构造一条种子答题记录；只用于统计/最近记录，不参与当前练习队列。
 * @param sentence 题目
 * @param pattern 作答质量：full / partial / wrong / reveal
 * @param daysAgo 距今天数
 * @param sequence 序号
 * @param userId 归属用户；缺省为演示学习者「2」
 * @returns mock attempt
 */
function seedAttempt(
  sentence: MockSentence,
  pattern: "full" | "partial" | "wrong" | "reveal",
  daysAgo: number,
  sequence: number,
  userId = "2",
): MockAttempt {
  const kind = pattern === "reveal" ? "REVEAL" : "FIRST";
  const slots = sentence.blank.map((chunkIndex, position) => {
    const chunk = sentence.chunks.find((item) => item.index === chunkIndex);
    const canonical = chunk?.answers[0] ?? "";
    let value = "";
    if (pattern === "full") {
      value = canonical;
    } else if (pattern === "partial") {
      value = position === 0 ? canonical : `${canonical.split(" ").slice(0, 1).join(" ")} wrong`;
    } else if (pattern === "wrong") {
      value = "something wrong";
    }
    return { chunkIndex, value };
  });

  const answer = {
    schemaVersion: 1 as const,
    mode: "CLOZE" as const,
    ...(pattern === "reveal" ? { revealed: true, slots: [] } : { slots }),
  };
  const result =
    pattern === "reveal"
      ? revealResult()
      : gradeCloze({
          sentence: sentence.zh,
          chunks: sentence.chunks,
          blank: sentence.blank,
          answers: slots.map((slot) => slot.value),
        });
  const createdAt = isoAt(-daysAgo, -1);
  const attemptId = `3f0e8400-e29b-41d4-a716-44665544${String(sequence).padStart(4, "0")}`;
  const review: Review = {
    id: String(100 + sequence),
    sentenceId: sentence.id,
    mode: "CLOZE",
    state: pattern === "full" ? "LEARNING" : "LEARNING",
    fsrsState: "LEARNING",
    dueAt: isoAt(1),
    stability: 2.4,
    difficulty: 5.3,
    reps: 1,
    lapses: pattern === "full" ? 0 : 1,
    scheduledDays: 1,
    rating: pattern === "full" ? 3 : 2,
    lastReviewedAt: createdAt,
  };
  const request: AttemptRequest = {
    attemptId,
    sentenceId: sentence.id,
    sentenceVersionId: sentence.sentenceVersionId,
    mode: "CLOZE",
    kind,
    retryOfId: null,
    answer,
    hintsUsed: 0,
    durationMs: 45_000,
    clientCreatedAt: createdAt,
  };
  const response: AttemptResponse = {
    attemptId,
    sentenceId: sentence.id,
    sentenceVersionId: sentence.sentenceVersionId,
    mode: "CLOZE",
    kind,
    score: result.score,
    maxScore: 100,
    result,
    grading: {
      id: String(500 + sequence),
      sequence: 1,
      grader: "LOCAL",
      normalizerVersion: "norm-1",
      rubricVersion: "rubric-1",
      createdAt,
    },
    review,
    reference: { ref: sentence.ref, note: sentence.note ?? null },
    nextAction: {
      retryRequired: result.score !== null && result.score < 100 && kind === "FIRST",
      canReveal: kind !== "REVEAL",
    },
    replayed: false,
  };
  return {
    userId,
    request,
    response,
    createdAt,
    zh: sentence.zh,
  };
}

/**
 * 构造默认邀请码种子；注册端点与用户页邀请码区共用同一份数据。
 * @returns 邀请码列表
 */
export function seedInvites(): MockInvite[] {
  return [
    {
      code: "YL-DEMO-2026",
      used: false,
      usedBy: null,
      createdAt: isoAt(-30),
    },
  ];
}

function seedAuditLogs(): AuditLog[] {
  // 每行末位是 targetLabel（对象的人类可读名）；TODO(mock): 演示数据，真实接口落地后删除。
  const items: Array<
    [string, string, string | null, string | null, string | null, string, string | null]
  > = [
    ["admin", "USER_DISABLE", "USER", "3", "用户申请暂停账号", "2 天前", "quietuser"],
    [
      "content",
      "IMPORT_COMMIT",
      "IMPORT_BATCH",
      "8f14e45f-ceea-467f-9f0b-1a2b3c4d5e6f",
      "首次导入",
      "3 天前",
      "官方题库 · TEM8 翻译",
    ],
    ["admin", "ROLE_CHANGE", "USER", "4", "团队调整", "4 天前", "translator"],
    ["content", "BANK_CREATE", "BANK", "2", "创建私有题库", "5 天前", "写作常用句"],
    [
      "admin",
      "VIEW_ATTEMPT_RAW",
      "ATTEMPT",
      "3f0e8400-e29b-41d4-a716-446655440002",
      "评分申诉",
      "6 天前",
      "learner · 会议被推迟到下周三。",
    ],
    ["account", "LOGIN", "USER", "2", null, "今天", "learner"],
    [
      "content",
      "SENTENCE_DISABLE",
      "SENTENCE",
      "01J9Z6K2M8Q4R7T1V3W5X7Y9A7",
      "内容有误",
      "8 天前",
      "越来越多的人开始关注环境保护。",
    ],
    ["admin", "USER_ENABLE", "USER", "3", "用户恢复使用", "9 天前", "quietuser"],
    ["account", "PASSWORD_CHANGE", "USER", "2", null, "10 天前", "learner"],
    [
      "content",
      "IMPORT_ROLLBACK",
      "IMPORT_BATCH",
      "6f14e45f-ceea-467f-9f0b-1a2b3c4d5e6f",
      "内容有误",
      "12 天前",
      "官方题库 · TEM8 翻译",
    ],
  ];
  return items.map(([module, action, targetType, targetId, reason, when, targetLabel], index) => ({
    id: String(900 + index),
    module,
    actorId: "1",
    actorName: "admin",
    actorRole: "ADMIN",
    clientIp: "127.0.0.1",
    action,
    targetType,
    targetId,
    targetLabel, // TODO(mock): 演示数据，真实接口落地后删除
    reason,
    outcome: "SUCCESS",
    durationMs: 8 + index * 3,
    createdAt: isoAt(when === "今天" ? 0 : -Number.parseInt(when, 10), -index),
  }));
}

function seedErrorLogs(): ErrorLogDetail[] {
  const items: Array<[string, string, string, string, string, string]> = [
    [
      "practice",
      "java.lang.IllegalStateException",
      "grading result missing annotations",
      "POST",
      "/api/practice/attempts",
      "2 小时前",
    ],
    [
      "content",
      "java.io.UncheckedIOException",
      "failed to read import file",
      "POST",
      "/api/admin/imports/preview",
      "1 天前",
    ],
    [
      "account",
      "org.springframework.dao.DataAccessResourceFailureException",
      "database connection reset",
      "POST",
      "/api/auth/login",
      "2 天前",
    ],
    [
      "analytics",
      "java.lang.ArithmeticException",
      "/ by zero while aggregating accuracy",
      "GET",
      "/api/analytics/accuracy",
      "3 天前",
    ],
    [
      "admin",
      "java.lang.IllegalArgumentException",
      "unknown import row status",
      "GET",
      "/api/admin/error-logs",
      "5 天前",
    ],
    [
      "system",
      "java.util.concurrent.TimeoutException",
      "redis command timed out",
      "GET",
      "/actuator/health",
      "6 天前",
    ],
  ];
  return items.map(([module, exceptionClass, message, method, path, when], index) => ({
    id: String(700 + index),
    traceId: `trace-${String(index + 1).padStart(4, "0")}`,
    module: module as ErrorLogDetail["module"],
    exceptionClass,
    message,
    stackTrace: `${exceptionClass}: ${message}\n\tat io.github.codejinxz.yilian.${module}.InternalHandler.handle(InternalHandler.java:${40 + index})\n\tat java.base/java.lang.Thread.run(Thread.java:1583)`,
    requestMethod: method,
    requestPath: path,
    userId: index % 2 === 0 ? "2" : null,
    operator: index % 2 === 0 ? "learner" : null,
    clientIp: "127.0.0.1",
    createdAt: isoAt(when.includes("小时") ? 0 : -Number.parseInt(when, 10), -index),
  }));
}

function seedImports(): MockImportBatch[] {
  const previewRows: ImportRow[] = [
    {
      rowNo: 1,
      externalKey: "s_0001",
      status: "VALID",
      errors: [],
      sentenceId: null,
      sentenceVersionId: null,
    },
    {
      rowNo: 2,
      externalKey: "s_0002",
      status: "VALID",
      errors: [],
      sentenceId: null,
      sentenceVersionId: null,
    },
    {
      rowNo: 3,
      externalKey: "s_0003",
      status: "INVALID",
      errors: [
        {
          code: "CHUNK_REF_MISMATCH",
          field: "chunks",
          message: "chunks[].answers[0] 按序拼接与 ref 不一致",
        },
        { code: "NOTE_TOO_SHORT", field: "note", message: "解析至少 20 字" },
      ],
      sentenceId: null,
      sentenceVersionId: null,
    },
    {
      rowNo: 4,
      externalKey: "s_0004",
      status: "VALID",
      errors: [],
      sentenceId: null,
      sentenceVersionId: null,
    },
    {
      rowNo: 5,
      externalKey: "s_0005",
      status: "VALID",
      errors: [],
      sentenceId: null,
      sentenceVersionId: null,
    },
  ];
  const committedRows: ImportRow[] = Array.from({ length: 10 }, (_, index) => ({
    rowNo: index + 1,
    externalKey: `c_${String(index + 1).padStart(4, "0")}`,
    status: "IMPORTED",
    errors: [],
    sentenceId: mockUlid(50 + index),
    sentenceVersionId: String(3000 + index),
  }));
  return [
    {
      batchId: "8f14e45f-ceea-467f-9f0b-1a2b3c4d5e6f",
      bankId: "1",
      format: "JSON",
      originalFilename: "unit-5.json",
      sizeBytes: 48_200,
      status: "PREVIEWED",
      totalRows: 5,
      validRows: 4,
      invalidRows: 1,
      importedRows: 0,
      createdAt: isoAt(-1, -2),
      previewedAt: isoAt(-1, -2),
      committedAt: null,
      rolledBackAt: null,
      errorSummary: {
        schemaVersion: 1,
        code: "ROW_VALIDATION_FAILED",
        message: "1 行未通过校验",
        sampleRows: [3],
      },
      rows: previewRows,
      rawRows: {},
    },
    {
      batchId: "6f14e45f-ceea-467f-9f0b-1a2b3c4d5e6f",
      bankId: "1",
      format: "CSV",
      originalFilename: "review-02.csv",
      sizeBytes: 31_900,
      status: "COMMITTED",
      totalRows: 10,
      validRows: 10,
      invalidRows: 0,
      importedRows: 10,
      createdAt: isoAt(-3),
      previewedAt: isoAt(-3),
      committedAt: isoAt(-3, 1),
      rolledBackAt: null,
      errorSummary: null,
      rows: committedRows,
      rawRows: {},
    },
  ];
}

/**
 * 构造上传批次种子：一个已完成（带 2 行失败）与一个失败待继续，覆盖上传页的主要状态。
 * @returns 上传批次列表
 */
function seedUploads(): MockUploadBatch[] {
  return [
    {
      batchId: "upload-0001",
      originalFilename: "unit-4.json",
      bankId: "1", // TODO(mock): 演示数据，真实接口落地后删除
      sizeBytes: 26_400,
      status: "COMPLETED",
      totalRows: 24,
      processedRows: 24,
      successRows: 22,
      failedRows: 2,
      errorMessage: null,
      createdAt: isoAt(-2, -3),
    },
    {
      batchId: "upload-0002",
      originalFilename: "unit-5.csv",
      bankId: "1", // TODO(mock): 演示数据，真实接口落地后删除
      sizeBytes: 18_750,
      status: "FAILED",
      totalRows: 12,
      processedRows: 5,
      successRows: 4,
      failedRows: 1,
      errorMessage: "第 6 行解析非法，批次已暂停",
      createdAt: isoAt(-1, -4),
    },
  ];
}

/**
 * 构造全新的演示数据；日期相对当前时间，保证「今日/到期」始终非空。
 * @returns 初始 mock 数据库
 */
export function createSeedDb(): MockDb {
  const sentences = seedSentences();
  const reviews: MockReview[] = [
    makeReview("10", sentences[0]!, "LEARNING", isoAt(0, -1), { reps: 2, lapses: 1 }),
    makeReview("11", sentences[1]!, "LEARNING", isoAt(2), { reps: 1 }),
    makeReview("12", sentences[2]!, "MASTERED", isoAt(20), {
      reps: 6,
      scheduledDays: 20,
      stability: 40,
    }),
    makeReview("13", sentences[3]!, "NEW", isoAt(0, -2)),
    makeReview("14", sentences[4]!, "LEARNING", isoAt(-2), { reps: 2, lapses: 2 }),
    makeReview("15", sentences[5]!, "MASTERED", isoAt(30), {
      reps: 6,
      scheduledDays: 30,
      stability: 60,
    }),
    makeReview("16", sentences[6]!, "LEARNING", isoAt(1), { reps: 1 }),
    makeReview("17", sentences[7]!, "NEW", isoAt(0)),
    makeReview("18", sentences[8]!, "NEW", isoAt(0)),
    makeReview("19", sentences[9]!, "LEARNING", isoAt(3), { reps: 2 }),
    makeReview("20", sentences[10]!, "NEW", isoAt(0)),
    makeReview("21", sentences[11]!, "NEW", isoAt(0)),
  ];

  const patterns: Array<"full" | "partial" | "wrong" | "reveal"> = [
    "full",
    "partial",
    "wrong",
    "full",
    "partial",
    "reveal",
    "full",
    "wrong",
    "partial",
    "full",
    "wrong",
    "partial",
    "full",
    "partial",
    "full",
  ];
  const attempts = patterns.map((pattern, index) => {
    const daysAgo = index < 2 ? 0 : index - 1;
    return seedAttempt(sentences[(index + 3) % sentences.length]!, pattern, daysAgo, index + 1);
  });
  // 管理端用户抽屉需要非空样本：给译者/沉默用户与管理员各补几条历史作答。
  attempts.push(
    seedAttempt(sentences[1]!, "full", 0, 101, "4"),
    seedAttempt(sentences[3]!, "partial", 0, 102, "4"),
    seedAttempt(sentences[5]!, "wrong", 1, 103, "4"),
    seedAttempt(sentences[2]!, "full", 2, 104, "3"),
    seedAttempt(sentences[6]!, "wrong", 3, 105, "3"),
    seedAttempt(sentences[8]!, "full", 0, 106, "1"),
  );

  const partialAttempt = attempts.find((attempt) =>
    attempt.response.result.annotations.some((annotation) => !annotation.correct),
  );
  const appealKey = partialAttempt?.response.result.annotations.find(
    (annotation) => !annotation.correct,
  )?.key;

  return {
    version: 1,
    sessionUserId: null,
    users: seedUsers(),
    banks: seedBanks(),
    sentences,
    reviews,
    attempts,
    appeals:
      partialAttempt && appealKey
        ? [
            {
              id: "30",
              attemptId: partialAttempt.request.attemptId,
              annotationKey: appealKey,
              kind: "DISPUTE",
              status: "ACCEPTED",
              proposedAnswer: null,
              reason: "我认为这个搭配也可以接受",
            },
          ]
        : [],
    auditLogs: seedAuditLogs(),
    errorLogs: seedErrorLogs(),
    imports: seedImports(),
    uploads: seedUploads(),
    invites: seedInvites(),
    seq: 1000,
    inviteUsedBy: null,
  };
}

let db: MockDb | null = null;

/**
 * 读取 mock 数据库；首次调用时从 localStorage 恢复，否则写入种子数据。
 * @returns 当前 mock 数据库
 */
export function getMockDb(): MockDb {
  if (db) {
    return db;
  }
  if (typeof localStorage !== "undefined") {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      try {
        const parsed = JSON.parse(stored) as MockDb;
        if (parsed.version === 1 && Array.isArray(parsed.sentences)) {
          // 历史存储没有 uploads 字段：补空数组而不是整体重置，避免清掉用户的演示进度。
          if (!Array.isArray(parsed.uploads)) {
            parsed.uploads = [];
          }
          // 历史存储没有 invites 字段：补默认码，注册与用户页演示不受影响。
          if (!Array.isArray(parsed.invites)) {
            parsed.invites = seedInvites();
          }
          db = parsed;
          return db;
        }
      } catch {
        localStorage.removeItem(STORAGE_KEY);
      }
    }
  }
  db = createSeedDb();
  persistMockDb();
  return db;
}

/**
 * 把内存数据写回 localStorage；隐私模式或配额失败时静默降级，不影响内存演示。
 */
export function persistMockDb(): void {
  if (!db || typeof localStorage === "undefined") {
    return;
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(db));
  } catch {
    // 存储不可用时保持内存态，页面仍可继续操作。
  }
}

/**
 * 重置演示数据与登录态；测试与「重置演示数据」按钮使用。
 */
export function resetMockDb(): void {
  db = createSeedDb();
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem(STORAGE_KEY);
  }
  persistMockDb();
}

/**
 * 生成新的自增 id，避免 mock 中重复。
 * @returns 自增数字字符串
 */
export function nextMockId(): string {
  const state = getMockDb();
  state.seq += 1;
  return String(state.seq);
}

/**
 * 分页切片；页码越界时回落到最后一页，与服务端 PageResponse 语义接近。
 * @param items 全量条目
 * @param page 页码（从 1 开始）
 * @param size 每页条数
 * @returns PageResponse
 */
export function paginate<T>(
  items: T[],
  page: number,
  size: number,
): {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  hasNext: boolean;
} {
  const total = items.length;
  const totalPages = Math.max(1, Math.ceil(total / size));
  const safePage = Math.min(Math.max(1, page), totalPages);
  const start = (safePage - 1) * size;
  return {
    items: items.slice(start, start + size),
    page: safePage,
    size,
    total,
    totalPages,
    hasNext: safePage < totalPages,
  };
}
