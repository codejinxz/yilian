import { describe, expect, it } from "vitest";
import {
  gradeCloze,
  gradeFree,
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
