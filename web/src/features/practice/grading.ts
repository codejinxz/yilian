import type { GradingAnnotation, GradingResult } from "@/lib/schemas/practice";

/** 语块填空判分的输入；页面只需要题干、语块与用户逐槽答案。 */
export interface GradingChunk {
  index: number;
  zh: string;
  answers: string[];
  tag?: string | null;
  note?: string | null;
}

export interface ClozeGradingInput {
  /** 中文原句；只用于结果摘要，不参与逐词比对。 */
  sentence: string;
  chunks: GradingChunk[];
  /** 挖空语块下标，顺序即题面编号顺序。 */
  blank: number[];
  /** 与 blank 对齐的答案数组，或按 chunkIndex 索引的答案记录。 */
  answers: readonly string[] | Record<number, string>;
}

const FULL_WIDTH_START = 0xff01;
const FULL_WIDTH_END = 0xff5e;
const FULL_WIDTH_OFFSET = 0xfee0;
const TRAILING_PUNCTUATION = /[.!?,;:。！？，、；：…]+$/;

/**
 * 归一化答案：全角转半角、弯引号转直引号、转小写、压缩空白、去句末标点。
 * 与服务端 normalizer 的 v1 语义保持一致，仅用于本地即时反馈。
 * @param value 用户输入或可接受答案
 * @returns 用于比较的规范字符串
 */
export function normalizeAnswer(value: string): string {
  let normalized = value.normalize("NFKC");

  normalized = normalized.replace(/[\u2018\u2019\u201b\u2032]/g, "'");
  normalized = normalized.replace(/[\u201c\u201d\u201f\u2033]/g, '"');
  normalized = normalized.replace(/\u3000/g, " ");

  let converted = "";
  for (const char of normalized) {
    const code = char.charCodeAt(0);
    if (code >= FULL_WIDTH_START && code <= FULL_WIDTH_END) {
      converted += String.fromCharCode(code - FULL_WIDTH_OFFSET);
    } else {
      converted += char;
    }
  }

  return converted
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim()
    .replace(/([.!?。！？]+)(["']?)$/g, "$2")
    .replace(TRAILING_PUNCTUATION, "")
    .trim();
}

/**
 * 按空白切词；空字符串返回空数组。
 * @param value 已归一化或原始输入
 * @returns 归一化后的词数组
 */
export function tokenizeAnswer(value: string): string[] {
  const normalized = normalizeAnswer(value);
  return normalized === "" ? [] : normalized.split(" ");
}

/**
 * 读取某个挖空语块对应槽位的答案。
 * @param answers 数组或记录
 * @param position blank 数组中的位置
 * @param chunkIndex 语块下标
 * @returns 用户输入，缺省为空串
 */
function readAnswer(
  answers: ClozeGradingInput["answers"],
  position: number,
  chunkIndex: number,
): string {
  if (Array.isArray(answers)) {
    return answers[position] ?? "";
  }
  return answers[chunkIndex] ?? "";
}

/**
 * 生成批注文案；保持「考点 + 错误」的格式，便于结果显示可解释性。
 * @param tag 考点标签
 * @param received 用户输入
 * @param correct 是否整块命中
 * @returns 可展示的批注或 null
 */
function annotationMessage(tag: string, received: string, correct: boolean): string | null {
  if (correct) {
    return null;
  }
  if (normalizeAnswer(received) === "") {
    return "未填写";
  }
  return tag.endsWith("错误") ? tag : `${tag}错误`;
}

/**
 * 语块填空本地判分。整块命中任一可接受答案即该语块全对；否则按规范答案逐词位置比对，
 * 部分对 = 命中词数 / 总词数 × 100。函数无副作用，供页面在提交前即时反馈。
 * @param input 题干、语块、挖空下标与逐槽答案
 * @returns 0–100 的分数、逐槽批注与一句摘要
 */
export function gradeCloze(input: ClozeGradingInput): GradingResult {
  const annotations: GradingAnnotation[] = [];
  let hitCount = 0;
  let totalCount = 0;

  input.blank.forEach((chunkIndex, position) => {
    const chunk = input.chunks.find((item) => item.index === chunkIndex);
    const received = readAnswer(input.answers, position, chunkIndex);
    const normalizedReceived = normalizeAnswer(received);

    if (!chunk) {
      annotations.push({
        key: `slot:${chunkIndex}`,
        chunkIndex,
        tag: "未分类",
        expected: [],
        received,
        correct: false,
        message: "语块不存在",
        minimalFix: null,
      });
      return;
    }

    const expected = chunk.answers.filter((answer) => normalizeAnswer(answer) !== "");
    const canonical = expected[0] ?? "";
    const tag = chunk.tag?.trim() || "未分类";
    const wholeHit =
      normalizedReceived !== "" &&
      expected.some((answer) => normalizeAnswer(answer) === normalizedReceived);

    const expectedTokens = tokenizeAnswer(canonical);
    totalCount += expectedTokens.length;

    let matched = 0;
    if (wholeHit) {
      matched = expectedTokens.length;
    } else {
      const receivedTokens = tokenizeAnswer(received);
      const limit = Math.min(expectedTokens.length, receivedTokens.length);
      for (let index = 0; index < limit; index += 1) {
        if (expectedTokens[index] === receivedTokens[index]) {
          matched += 1;
        }
      }
    }
    hitCount += matched;

    annotations.push({
      key: `slot:${chunkIndex}`,
      chunkIndex,
      tag,
      expected,
      received,
      correct: wholeHit,
      message: annotationMessage(tag, received, wholeHit),
      minimalFix: wholeHit ? null : canonical || null,
    });
  });

  const score = totalCount === 0 ? 0 : Math.round((hitCount / totalCount) * 100);
  const missed = annotations.filter((annotation) => !annotation.correct).length;
  const summary =
    annotations.length === 0
      ? "没有需要判定的语块"
      : missed === 0
        ? "全部命中"
        : `还差 ${missed} 处`;

  return {
    schemaVersion: 1,
    score,
    maxScore: 100,
    hitCount,
    totalCount,
    annotations,
    summary,
  };
}

/**
 * 整句本地粗判：完全一致给 100；否则按参考译文词形的多重集覆盖率给部分分。
 * 仅用于离线降级与 mock；真实深度批改仍由服务端完成。
 * @param input 中文原句、参考译文与用户译文
 * @returns 与填空结果同结构的批注结果
 */
export function gradeFree(input: { sentence: string; ref: string; text: string }): GradingResult {
  const expected = tokenizeAnswer(input.ref);
  const received = tokenizeAnswer(input.text);
  const exact =
    normalizeAnswer(input.ref) !== "" && normalizeAnswer(input.ref) === normalizeAnswer(input.text);

  const pool = [...received];
  let hitCount = 0;
  for (const token of expected) {
    const index = pool.indexOf(token);
    if (index >= 0) {
      hitCount += 1;
      pool.splice(index, 1);
    }
  }

  const totalCount = expected.length;
  const score = exact ? 100 : totalCount === 0 ? 0 : Math.round((hitCount / totalCount) * 100);
  const annotations: GradingAnnotation[] =
    exact || input.text.trim() === ""
      ? []
      : [
          {
            key: "free:0",
            chunkIndex: 0,
            tag: "整句",
            expected: [input.ref],
            received: input.text,
            correct: false,
            message: "与参考译文有差异",
            minimalFix: input.ref,
          },
        ];

  return {
    schemaVersion: 1,
    score,
    maxScore: 100,
    hitCount,
    totalCount,
    annotations,
    summary: exact ? "全部命中" : input.text.trim() === "" ? "未作答" : `要点覆盖 ${score}%`,
  };
}

/**
 * 判断重打的整句是否与参考译文一致；供「改对才过」使用。
 * @param reference 参考译文
 * @param retyped 用户重打内容
 * @returns 归一化后是否一致
 */
export function retypedMatchesReference(reference: string, retyped: string): boolean {
  return (
    normalizeAnswer(reference) !== "" && normalizeAnswer(reference) === normalizeAnswer(retyped)
  );
}

/**
 * 词边界短语匹配；整句批改「要点命中」chips 的判定核心。
 *
 * <p>与原型 submitFree/hasPhrase（prototype/index.html L1237）同语义：命中答案的每个语块打一枚
 * 绿勾 chip。归一差异：原型只归一短语、对原始全文匹配，多重空格或全角标点会导致漏判；
 * 这里把全文一并归一（NFKC/弯引号/全角/空白压缩/去句末标点）后再匹配，严格优于原型。
 *
 * @param text 用户整句译文
 * @param phrase 语块可接受答案
 * @returns 是否以独立词的形式出现在译文中
 */
export function hasPhrase(text: string, phrase: string): boolean {
  const normalizedPhrase = normalizeAnswer(phrase);
  if (normalizedPhrase === "") {
    return false;
  }
  const escaped = normalizedPhrase.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(^|[^a-z0-9])${escaped}($|[^a-z0-9])`, "i").test(normalizeAnswer(text));
}

/** 参考对位视图里的一个期望词：词面 + 是否被用户输入命中 + 在规范答案中的位置（渲染 key 用）。 */
export interface ReferenceWord {
  word: string;
  hit: boolean;
  position: number;
}

/** 一个挖空语块的参考对位结果：期望词逐词命中态与用户完整输入。 */
export interface ChunkAlignment {
  /** 规范答案逐词拆分后的期望词与命中态，顺序与词面一致。 */
  words: ReferenceWord[];
  /** 用户输入按空白切词；超出期望词数的部分同样保留，对位视图不丢字。 */
  receivedWords: string[];
}

/**
 * 「按参考对位」视图的逐词对位计算。
 *
 * <p>命中口径与判分展示保持可读优先：先按位置比对（与本地判分的部分分同口径），
 * 位置错开但词面存在的输入（多打了引导词导致整体后移）经贪心多重集匹配兜底记为命中——
 * 对位视图要回答的是「哪个词没写对」，分数仍以判分结果为准。
 *
 * @param answers 语块可接受答案列表（第一个为规范答案）
 * @param received 用户在该空的完整输入
 * @returns 期望词命中态与用户切词结果；无可接受答案时 words 为空
 */
export function alignToReference(answers: string[], received: string): ChunkAlignment {
  const expected = answers.filter((answer) => normalizeAnswer(answer) !== "");
  const canonical = expected[0] ?? "";
  const canonicalWords = canonical.trim() === "" ? [] : canonical.split(/\s+/).filter(Boolean);
  const receivedWords = received.trim() === "" ? [] : received.split(/\s+/).filter(Boolean);
  // 与可接受答案词数相同的备选答案，按位置提供同位置替身词（与骨架渲染的 alts 语义一致）。
  const altWordsByPosition = canonicalWords.map((_, position) =>
    expected
      .map((answer) => answer.split(/\s+/).filter(Boolean))
      .filter((words) => words.length === canonicalWords.length)
      .map((words) => words[position])
      .filter(Boolean),
  );

  const normalizedReceived = receivedWords.map(normalizeAnswer);
  const consumed = receivedWords.map(() => false);
  /** 用户第 receivedIndex 个输入词是否命中期望词（含同位置替身词）。 */
  const matchesExpected = (receivedIndex: number, word: string, position: number): boolean =>
    normalizedReceived[receivedIndex] === normalizeAnswer(word) ||
    altWordsByPosition[position].some(
      (alt) => normalizeAnswer(alt) === normalizedReceived[receivedIndex],
    );

  // 第一轮：按位置比对，与判分部分分同口径。
  const hitFlags = canonicalWords.map((word, position) => {
    if (receivedWords[position] && matchesExpected(position, word, position)) {
      consumed[position] = true;
      return true;
    }
    return false;
  });
  // 第二轮：位置错开的兜底匹配，只对仍未命中的期望词找未被占用的同词面输入。
  for (let position = 0; position < canonicalWords.length; position += 1) {
    if (hitFlags[position]) {
      continue;
    }
    const altSet = new Set(altWordsByPosition[position].map(normalizeAnswer));
    const matchIndex = normalizedReceived.findIndex(
      (typed, index) =>
        !consumed[index] &&
        (typed === normalizeAnswer(canonicalWords[position]) || altSet.has(typed)),
    );
    if (matchIndex >= 0) {
      consumed[matchIndex] = true;
      hitFlags[position] = true;
    }
  }

  return {
    words: canonicalWords.map((word, position) => ({
      word,
      hit: hitFlags[position],
      position,
    })),
    receivedWords,
  };
}

/** 一枚要点 chip：语块中文 + 是否命中。 */
export interface FreeChunkChip {
  index: number;
  zh: string;
  hit: boolean;
}

/**
 * 整句模式的「要点命中」chips 数据：句子全部语块逐一比对整句译文，命中任一可接受答案即命中。
 *
 * <p>数据源：chips 是展示层派生数据，句子 chunks 与提交文本页面本就有，
 * 原型也是客户端现算——批改响应保持紧凑，不为此新增字段；服务端批注（free:0 聚合一条）形状不变。
 *
 * @param chunks 句子语块（含可接受答案）
 * @param text 用户提交的整句译文；空串或未提交时返回空数组（整行不渲染）
 * @returns 逐语块命中结果，顺序与语块下标一致
 */
export function freeModeChips(
  chunks: GradingChunk[],
  text: string | null | undefined,
): FreeChunkChip[] {
  if (!text || text.trim() === "") {
    return [];
  }
  return chunks.map((chunk) => ({
    index: chunk.index,
    zh: chunk.zh,
    hit: chunk.answers.some((answer) => hasPhrase(text, answer)),
  }));
}
