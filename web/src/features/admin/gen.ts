/**
 * 出题页的纯函数：Prompt 模板拼装与粘贴 JSON 的前端校验。
 * 与原型 buildGenPrompt / validateGen 逐条对齐；不调用任何模型，也不读写数据库。
 */

/** 生成 Prompt 的输入。 */
export interface GenPromptInput {
  /** 目标 / 场景，如「大学生英语四级」 */
  target: string;
  /** 难度 id */
  levelId: string;
  /** 难度展示名 */
  levelLabel: string;
  /** 句数（字符串，直接进模板） */
  count: string;
  /** 已选考点；空数组表示不限 */
  tags: string[];
  /** 补充要求 */
  extra: string;
}

/**
 * 拼装出题 Prompt（中文模板，与原型一致）。
 * @param input 出题需求
 * @returns 完整 Prompt 文本
 */
export function buildGenPrompt(input: GenPromptInput): string {
  const target = input.target.trim() || "通用中译英练习";
  const tags = input.tags.length > 0 ? input.tags.join("、") : "不限";
  const extra = input.extra.trim();

  return (
    `你是一位英语教学与测试专家。请生成 ${input.count} 道「中译英」练习题，用于「${target}」，难度对应 ${input.levelLabel}。\n\n` +
    "要求：\n" +
    "1. 中文原句 15–40 字，语域贴近该场景；必须是原创句，不要复制任何真题、教材或网站原文。\n" +
    "2. 每句给出参考译文，英文自然地道，不要中式表达。\n" +
    "3. 把英文译文切成 3–6 个语块（chunk），每个语块给 1–3 种可接受译法。\n" +
    "4. 每句给 1–2 句中文解析（note），说明考点与易错点，不要复述译文。\n" +
    "5. 每句挑 1–2 个语块挖空，写进 blank 数组，下标从 0 开始。\n" +
    `6. 只考这些考点：${tags}。\n` +
    "7. 严格输出 JSON 数组，不要 Markdown 代码块，不要解释。\n" +
    (extra ? `8. 补充要求：${extra}\n` : "") +
    "\nJSON 结构：\n" +
    "[\n" +
    "  {\n" +
    '    "zh": "中文原句",\n' +
    '    "ref": "Reference English translation.",\n' +
    '    "note": "中文解析，说明考点与易错点。",\n' +
    '    "chunks": [\n' +
    '      { "zh": "中文语块", "answers": ["English chunk", "alternative"] }\n' +
    "    ],\n" +
    '    "blank": [1],\n' +
    '    "tags": ["搭配"],\n' +
    `    "level": "${input.levelId}"\n` +
    "  }\n" +
    "]\n\n" +
    "自检（必须全部满足）：\n" +
    "- 每个 chunk 的 answers[0] 按顺序用空格拼接，去掉标点后必须等于 ref。\n" +
    "- blank 的每个下标都能在 chunks 里找到。\n" +
    "- note 不少于 20 字，不能只写「注意搭配」这类空话。\n" +
    "- 英文里不要出现中文标点。"
  );
}

/** 宽松归一：小写、仅保留字母数字（原型的 normLoose），用于 chunks 拼接与 ref 比对。 */
function normLoose(value: unknown): string {
  return String(value ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

/** 单行校验结果。 */
export interface GenRowResult {
  row: number;
  zh: string;
  note: string;
  ok: boolean;
  /** 全部错误；为空表示通过。 */
  why: string;
}

/** 校验输出：逐行结果与可导入的合法条目。 */
export interface GenValidation {
  rows: GenRowResult[];
  valid: Array<Record<string, unknown>>;
}

/**
 * 校验粘贴的 JSON 数组；规则与原型一致：中文过短、缺 ref、缺解析、chunks 空、
 * 缺 answers、blank 越界、chunks 拼接与 ref 不一致、行内重复。
 * @param raw 粘贴的原始文本
 * @returns 校验结果；解析失败时返回单行错误
 */
export function validatePastedGeneration(raw: string): GenValidation {
  const trimmed = raw.trim();
  if (!trimmed) {
    return {
      rows: [{ row: 1, zh: "—", note: "", ok: false, why: "先粘贴 AI 返回的 JSON" }],
      valid: [],
    };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, ""));
  } catch (error) {
    return {
      rows: [
        {
          row: 1,
          zh: "—",
          note: "",
          ok: false,
          why: `JSON 解析失败：${error instanceof Error ? error.message : "格式不正确"}`,
        },
      ],
      valid: [],
    };
  }
  let items: unknown[] = [];
  if (Array.isArray(parsed)) {
    items = parsed;
  } else if (
    typeof parsed === "object" &&
    parsed !== null &&
    Array.isArray((parsed as { questions?: unknown }).questions)
  ) {
    items = (parsed as { questions: unknown[] }).questions;
  } else {
    items = [parsed];
  }

  const valid: Array<Record<string, unknown>> = [];
  const seen = new Set<string>();
  const rows = items.map((item, index) => {
    const rowNo = index + 1;
    const errors: string[] = [];
    if (typeof item !== "object" || item === null || Array.isArray(item)) {
      errors.push("不是对象");
    } else {
      const record = item as Record<string, unknown>;
      const zh = typeof record.zh === "string" ? record.zh : "";
      const ref = typeof record.ref === "string" ? record.ref : "";
      const note = typeof record.note === "string" ? record.note : "";
      const chunks = Array.isArray(record.chunks) ? record.chunks : [];
      const blank = Array.isArray(record.blank) ? record.blank : [];
      if (zh.length < 4) {
        errors.push("中文过短");
      }
      if (!ref) {
        errors.push("缺 ref");
      }
      if (note.length < 6) {
        errors.push("缺解析");
      }
      if (chunks.length === 0) {
        errors.push("chunks 为空");
      } else {
        chunks.forEach((chunk, chunkIndex) => {
          const answers =
            typeof chunk === "object" &&
            chunk !== null &&
            Array.isArray((chunk as Record<string, unknown>).answers)
              ? ((chunk as Record<string, unknown>).answers as unknown[])
              : [];
          if (answers.length === 0) {
            errors.push(`第 ${chunkIndex + 1} 块缺 answers`);
          }
        });
      }
      blank.forEach((value) => {
        if (typeof value !== "number" || value < 0 || value >= chunks.length) {
          errors.push("blank 下标越界");
        }
      });
      if (ref && chunks.length > 0) {
        const firstAnswers = chunks.map((chunk) =>
          typeof chunk === "object" &&
          chunk !== null &&
          Array.isArray((chunk as Record<string, unknown>).answers)
            ? ((chunk as Record<string, unknown>).answers as unknown[])
            : [],
        );
        const concat = firstAnswers.map((answers) => String(answers[0] ?? "")).join(" ");
        if (normLoose(concat) !== normLoose(ref)) {
          errors.push("chunks 拼接与 ref 不一致");
        }
      }
      if (zh && ref) {
        const fingerprint = `${normLoose(zh)}|${normLoose(ref)}`;
        if (seen.has(fingerprint)) {
          errors.push("与前面的行重复");
        } else {
          seen.add(fingerprint);
        }
      }
      if (errors.length === 0) {
        valid.push(record);
      }
    }
    const record =
      typeof item === "object" && item !== null ? (item as Record<string, unknown>) : {};
    return {
      row: rowNo,
      zh: typeof record.zh === "string" ? record.zh : "—",
      note: typeof record.note === "string" ? record.note : "",
      ok: errors.length === 0,
      why: errors.join("；"),
    };
  });
  return { rows, valid };
}
