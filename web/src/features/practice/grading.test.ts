import { describe, expect, it } from "vitest";
import {
  freeModeChips,
  gradeCloze,
  gradeFree,
  hasPhrase,
  normalizeAnswer,
  retypedMatchesReference,
  tokenizeAnswer,
} from "./grading";

const chunks = [
  { index: 0, zh: "他", answers: ["He"], tag: "代词", note: null },
  {
    index: 1,
    zh: "越来越",
    answers: ["more and more", "increasingly"],
    tag: "副词结构",
    note: null,
  },
  {
    index: 2,
    zh: "重视",
    answers: ["pay attention to", "attach importance to"],
    tag: "搭配",
    note: null,
  },
  { index: 3, zh: "这件事", answers: ["this"], tag: "代词", note: null },
];

const base = {
  sentence: "他越来越重视这件事。",
  chunks,
  blank: [1, 2],
};

describe("normalizeAnswer", () => {
  it("统一大小写、全角、弯引号、空白与句末标点", () => {
    expect(normalizeAnswer("  ＨＥＬＬＯ   World！ ")).toBe("hello world");
    expect(normalizeAnswer("“More   and more。”")).toBe('"more and more"');
    expect(normalizeAnswer("Don’t stop.")).toBe("don't stop");
  });

  it("只去掉句末标点，保留词内连字符与缩写", () => {
    expect(normalizeAnswer("small and medium-sized enterprises.")).toBe(
      "small and medium-sized enterprises",
    );
    expect(normalizeAnswer("state-of-the-art")).toBe("state-of-the-art");
  });

  it("切词忽略空串", () => {
    expect(tokenizeAnswer("  ")).toEqual([]);
    expect(tokenizeAnswer("Pay attention to")).toEqual(["pay", "attention", "to"]);
  });
});

describe("gradeCloze", () => {
  it("整块命中任一可接受答案即该语块全对", () => {
    const result = gradeCloze({ ...base, answers: ["increasingly", "attach importance to"] });
    expect(result.score).toBe(100);
    expect(result.hitCount).toBe(6);
    expect(result.totalCount).toBe(6);
    expect(result.annotations.every((annotation) => annotation.correct)).toBe(true);
    expect(result.summary).toBe("全部命中");
  });

  it("整块命中不会因为词数不同而扣分", () => {
    const result = gradeCloze({ ...base, answers: ["increasingly", "pay attention to"] });
    expect(result.score).toBe(100);
    expect(result.annotations[0]?.expected).toContain("increasingly");
  });

  it("部分对按命中词数 / 总词数计分", () => {
    const result = gradeCloze({ ...base, answers: ["more and more", "pay attentions to"] });
    expect(result.hitCount).toBe(5);
    expect(result.totalCount).toBe(6);
    expect(result.score).toBe(83);
    expect(result.annotations[0]?.correct).toBe(true);
    expect(result.annotations[1]?.correct).toBe(false);
    expect(result.annotations[1]?.message).toBe("搭配错误");
    expect(result.annotations[1]?.minimalFix).toBe("pay attention to");
    expect(result.summary).toBe("还差 1 处");
  });

  it("全错给 0 分并逐槽标注未填写", () => {
    const result = gradeCloze({ ...base, answers: ["", ""] });
    expect(result.score).toBe(0);
    expect(result.hitCount).toBe(0);
    expect(result.annotations.map((annotation) => annotation.message)).toEqual([
      "未填写",
      "未填写",
    ]);
    expect(result.summary).toBe("还差 2 处");
  });

  it("标点与空格差异不影响判定", () => {
    const result = gradeCloze({
      ...base,
      answers: ["  More   and more. ", "pay attention to!"],
    });
    expect(result.score).toBe(100);
  });

  it("支持按 chunkIndex 传入答案对象", () => {
    const result = gradeCloze({
      ...base,
      answers: { 1: "more and more", 2: "pay attention to" },
    });
    expect(result.score).toBe(100);
  });

  it("不修改输入", () => {
    const answers = ["more and more", "pay attentions to"];
    const snapshot = [...answers];
    gradeCloze({ ...base, answers });
    expect(answers).toEqual(snapshot);
  });

  it("单次判分远低于 50ms 的性能预算", () => {
    const input = {
      ...base,
      answers: ["more and more", "pay attentions to"],
    };
    const iterations = 500;
    const start = performance.now();
    for (let index = 0; index < iterations; index += 1) {
      gradeCloze(input);
    }
    const average = (performance.now() - start) / iterations;
    expect(average).toBeLessThan(50);
  });
});

describe("gradeFree", () => {
  it("归一化后完全一致给 100", () => {
    const result = gradeFree({
      sentence: "他越来越重视这件事。",
      ref: "He is paying more and more attention to this.",
      text: "he is paying more and more attention to this",
    });
    expect(result.score).toBe(100);
    expect(result.annotations).toEqual([]);
  });

  it("词形覆盖不足给部分分", () => {
    const result = gradeFree({
      sentence: "他越来越重视这件事。",
      ref: "He is paying more and more attention to this.",
      text: "He pays more attention to this.",
    });
    expect(result.score).toBeGreaterThan(0);
    expect(result.score).toBeLessThan(100);
    expect(result.annotations).toHaveLength(1);
  });
});

describe("retypedMatchesReference", () => {
  it("忽略大小写、标点与多余空格", () => {
    expect(
      retypedMatchesReference(
        "He is paying more and more attention to this.",
        " he IS paying more and more attention to this ",
      ),
    ).toBe(true);
    expect(retypedMatchesReference("He is paying attention.", "He pays attention.")).toBe(false);
  });
});

describe("hasPhrase", () => {
  it("[AC-6c] 词边界命中：独立成词才中，子串不算（paying 不算命中 pay，与原型同语义）", () => {
    expect(hasPhrase("You must pay attention to this.", "pay attention to")).toBe(true);
    expect(hasPhrase("He is repaying attention to it.", "pay attention to")).toBe(false);
    expect(hasPhrase("this matters", "this")).toBe(true);
    expect(hasPhrase("these matters", "this")).toBe(false);
  });

  it("[AC-6c] 归一后匹配：大小写/多余空格/全角标点不漏判（优于原型的全文不归一）", () => {
    expect(hasPhrase("You MUST  PAY ATTENTION TO it！", "Pay Attention To")).toBe(true);
    expect(hasPhrase("this matters.", "this")).toBe(true);
  });

  it("[AC-6c] 空短语与空译文一律不命中", () => {
    expect(hasPhrase("anything", "  ")).toBe(false);
    expect(hasPhrase("", "word")).toBe(false);
  });
});

describe("freeModeChips", () => {
  const freeChunks = [
    { index: 0, zh: "他", answers: ["He"] },
    { index: 1, zh: "越来越", answers: ["more and more", "increasingly"] },
    { index: 2, zh: "重视", answers: ["pay attention to", "attach importance to"] },
  ];

  it("[AC-6c] 逐语块命中：命中任一可接受答案即绿勾", () => {
    const chips = freeModeChips(freeChunks, "He will increasingly pay attention to this.");
    expect(chips.map((chip) => chip.hit)).toEqual([true, true, true]);
  });

  it("[AC-6c] 部分命中：未中的语块标灰（miss）", () => {
    const chips = freeModeChips(freeChunks, "He noticed the change quietly.");
    expect(chips.map((chip) => chip.hit)).toEqual([true, false, false]);
  });

  it("[AC-6c] 未提交或空串：返回空数组（整行不渲染）", () => {
    expect(freeModeChips(freeChunks, null)).toEqual([]);
    expect(freeModeChips(freeChunks, "   ")).toEqual([]);
  });
});
