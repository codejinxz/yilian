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
