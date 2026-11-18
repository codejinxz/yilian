#!/usr/bin/env node
/**
 * 注释纪律 lint（node 零依赖）：扫描 TS/TSX 注释与 Flyway 迁移 SQL 头注释中的过程性引用。
 *
 * 职责说明：脚本由 web 的 `check:comments` 调用（CI web job 执行），但除了 web/src 之外
 * 也顺带扫描 server/src/main/resources/db/migration/*.sql 的注释行——server 侧的 Java
 * 注释由 Checkstyle（IllegalTokenText/RegexpSinglelineJava）在 mvnw test 的 validate 阶段
 * 强制，SQL 注释没有对应工具，由本脚本覆盖。禁词表与 server/config/checkstyle/checkstyle.xml
 * 保持同源，规则注释见该文件。
 *
 * 判定标准（与 GAP-ANALYSIS「038 注释纪律清扫+lint 规则」行同源，及
 * server/config/checkstyle/checkstyle.xml 的同名规则注释）：
 * - 禁止：批次号（0XX 形态）、评审轮次/条目号（R1/R2/S4/A-xx/F-xx/DEF-xx）、指示来源
 *   （任务书/计划文件/主人/pending-20261117/next-session-prompt）、gitignored 文档引用
 *   （docs/plans/、docs/retro/、docs/analysis/）、阶段词（本批/本迭代/消缺/遗留登记）。
 * - 保留（白名单，靠模式设计排除）：[AC-x] 测试标签（匹配前整行剥除该标签）、
 *   DATA-MODEL §/PRD §/API.md §/AGENTS § 章节指针、设计决策号 D-xxx（连字符前缀被
 *   数字模式的负向后顾排除）、S3/R2/S1 等协议与算法名、小数与 10_000 类数字（负向
 *   后顾排除）。
 * - 只扫注释（// 与 /* *\/ 与 --），字符串字面量（如测试标题 it("015 AC-4: …")）不扫。
 */

import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(scriptDir, "..");

/** web 前端源码目录与迁移 SQL 目录。 */
const WEB_SRC = join(repoRoot, "web", "src");
const SQL_DIR = join(repoRoot, "server", "src", "main", "resources", "db", "migration");

/**
 * 禁词规则表：与 checkstyle.xml 的 IllegalTokenText / RegexpSinglelineJava 模式一一对应。
 * 每条 rule = { pattern, message }；pattern 全部在剥除 [AC-x] 标签后的注释文本上执行。
 * @type {Array<{pattern: RegExp, message: string}>}
 */
const RULES = [
  {
    pattern: /本批(?!次)/u,
    message: "注释禁止阶段词「本批」（域内「本批次」除外）",
  },
  {
    pattern: /任务书|计划文件|next-session-prompt|pending-20261117|主人/u,
    message: "注释禁止引用指示来源",
  },
  {
    pattern: /docs\/(plans|retro|analysis)\//u,
    message: "注释禁止引用 gitignored 文档（docs/plans|retro|analysis）",
  },
  {
    pattern: /评审轮|轮巡|预披露|隔离审查|遗留登记|消缺/u,
    message: "注释禁止评审轮次/裁决过程引用",
  },
  {
    pattern: /实现注记|定位结论|体验报告/u,
    message: "注释禁止引用分析报告条目",
  },
  {
    pattern: /审查|巡检|冒烟|裁决点/u,
    message: "注释禁止评审/巡检过程词",
  },
  {
    // 批次号 0XX/0XXB/0XX-a：后接 CJK/括号/斜杠/字母；数字、小数点、下划线、连字符
    // 前缀的非批次数字（40306/0.025/10_000/D-001）被负向后顾排除。
    // JS 侧汉字属性写作 Script=Han（Java 侧为 \p{IsHan}，语义等价）。
    pattern:
      /(?<![0-9.\-_])0[0-9]{2}(?:[A-Z]|-[ab])?(?=\s*[\p{Script=Han}（(/：；，、。）A-Za-z])/u,
    message: "注释禁止批次号引用（0XX 形态）",
  },
  {
    pattern: /DEF-[0-9]|[AF]-0[0-9]|R[12]-[0-9F]/u,
    message: "注释禁止评审条目号引用（DEF-/A-/F-/R1-/R2-）",
  },
];

/** [AC-x] 测试标签：匹配前从注释文本中剥除，其余文本照常受检。 */
const AC_TAG = /\[AC-\d+\]/gu;

/**
 * 递归收集目录下指定扩展名的文件。
 *
 * @param {string} dir 起始目录
 * @param {string[]} extensions 允许的扩展名（含点）
 * @param {string[]} acc 累积结果
 * @returns {string[]} 文件绝对路径列表
 */
function collectFiles(dir, extensions, acc = []) {
  let entries = [];
  try {
    entries = readdirSync(dir);
  } catch {
    return acc;
  }
  for (const entry of entries) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      collectFiles(full, extensions, acc);
    } else if (extensions.some((ext) => entry.endsWith(ext))) {
      acc.push(full);
    }
  }
  return acc;
}

/**
 * 从 TS/TSX 源码中提取注释片段（跨行块注释按出现行号逐行拆出）。
 * 状态机跳过字符串字面量（'/"/` 与转义），字符串里的 // 与 /* 不算注释。
 *
 * @param {string} source 源码文本
 * @returns {Array<{line: number, text: string}>} 注释片段（text 为单行切片，含起止行）
 */
function extractTsComments(source) {
  const comments = [];
  const lines = source.split("\n");
  let inBlock = false;
  let blockText = "";
  let blockLine = 0;
  lines.forEach((line, index) => {
    const lineNo = index + 1;
    let i = 0;
    while (i < line.length) {
      const ch = line[i];
      const next = line[i + 1];
      if (inBlock) {
        if (ch === "*" && next === "/") {
          inBlock = false;
          i += 2;
          continue;
        }
        blockText += ch;
        i += 1;
        continue;
      }
      if (ch === "/" && next === "*") {
        inBlock = true;
        blockText = "";
        blockLine = lineNo;
        i += 2;
        continue;
      }
      if (ch === "/" && next === "/") {
        comments.push({ line: lineNo, text: line.slice(i + 2) });
        break;
      }
      if (ch === '"' || ch === "'" || ch === "`") {
        // 字符串字面量整体跳过（含转义符）。
        const quote = ch;
        i += 1;
        while (i < line.length) {
          if (line[i] === "\\") {
            i += 2;
            continue;
          }
          if (line[i] === quote) {
            i += 1;
            break;
          }
          i += 1;
        }
        continue;
      }
      i += 1;
    }
    if (inBlock) {
      // 行尾未闭合：把已累积的文本按行落账（含开头行）。
      comments.push({ line: blockLine, text: blockText });
      blockText = "";
      blockLine = lineNo + 1;
    }
  });
  return comments;
}

/**
 * 从 SQL 文本提取注释片段：-- 行注释与 /* *\/ 块注释。
 *
 * @param {string} source SQL 文本
 * @returns {Array<{line: number, text: string}>} 注释片段
 */
function extractSqlComments(source) {
  const comments = [];
  const lines = source.split("\n");
  let inBlock = false;
  lines.forEach((line, index) => {
    const lineNo = index + 1;
    if (inBlock) {
      const end = line.indexOf("*/");
      if (end === -1) {
        comments.push({ line: lineNo, text: line });
        return;
      }
      comments.push({ line: lineNo, text: line.slice(0, end) });
      inBlock = false;
      return;
    }
    const trimmed = line.trimStart();
    if (trimmed.startsWith("--")) {
      comments.push({ line: lineNo, text: trimmed.slice(2) });
      return;
    }
    const start = line.indexOf("/*");
    if (start !== -1) {
      const end = line.indexOf("*/", start + 2);
      if (end === -1) {
        inBlock = true;
        comments.push({ line: lineNo, text: line.slice(start + 2) });
        return;
      }
      comments.push({ line: lineNo, text: line.slice(start + 2, end) });
    }
  });
  return comments;
}

/** 收集待检文件与对应提取器。 */
const targets = [];
for (const file of collectFiles(WEB_SRC, [".ts", ".tsx"])) {
  targets.push({ file, extractor: extractTsComments });
}
for (const file of collectFiles(SQL_DIR, [".sql"])) {
  targets.push({ file, extractor: extractSqlComments });
}

/** 违规收集器。 */
const violations = [];
for (const { file, extractor } of targets) {
  const source = readFileSync(file, "utf8");
  for (const { line, text } of extractor(source)) {
    const stripped = text.replace(AC_TAG, "");
    for (const { pattern, message } of RULES) {
      const match = stripped.match(pattern);
      if (match) {
        violations.push(
          `${relative(repoRoot, file)}:${line}: ${message}——命中「${match[0]}」`,
        );
      }
    }
  }
}

if (violations.length > 0) {
  console.error(`注释纪律检查失败：${violations.length} 处违规`);
  for (const item of violations) {
    console.error(`  ${item}`);
  }
  console.error("注释只写「这段代码做什么/为什么这样设计」，禁止过程性引用（批次号/评审条目/指示来源/gitignored 文档/阶段词）。");
  process.exit(1);
}
console.log(`注释纪律检查通过：${targets.length} 个文件，0 违规。`);
