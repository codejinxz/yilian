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
 * 判定标准（与 server/config/checkstyle/checkstyle.xml 的同名规则注释同源；
 * 两处禁词表互指口径，新增模式两侧一致）：
 * - 禁止：批次号（0XX/0XXX 形态）、里程碑/路线图编号（M[0-9]）、评审轮次/条目号
 *   （R1/R2/S4/A-xx/F-xx/DEF-xx）、冒烟运行与波次引用（browser-smoke/波次/首轮/二轮/第 N 轮）、
 *   指示来源（任务书/计划文件/主人/pending-20261117/next-session-prompt）、gitignored 文档引用
 *   （docs/plans/、docs/retro/、docs/analysis/、见 GAP、已在 API §、见 docs/）、
 *   阶段词（本批/本迭代/消缺/遗留登记/后续接入/端点就绪后/先给入口）、
 *   风险披露旁白（已知限制/如实标注/如实描述/登记在/已在…登记）、
 *   mock 对照（mock 漂移/与 mock…一致/与 mock…同款/引擎与 mock 斜杠对照）。
 * - 保留（白名单，靠模式设计排除）：[AC-x] 测试标签（匹配前整行剥除该标签）、
 *   DATA-MODEL §/PRD §/API.md §/AGENTS § 章节指针、设计决策号 D-xxx（连字符前缀被
 *   数字模式的负向后顾排除）、S3/R2/S1 等协议与算法名、小数与 10_000 类数字（负向
 *   后顾排除）。
 * - 注释层只扫注释（// 与 /* *\/ 与 --）；字符串字面量不在注释模式下扫，但测试标题
 *   （it/test/describe 首参与 Java @DisplayName）由独立的标题检测兜底（见 scanTitlePrefix）。
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
  {
    // 里程碑/路线图编号 M2/M3（含 M2.1）：后接 CJK 即计划引用语义。
    // TEM8 等考试代码由前置字母负向后顾排除；M3_ROUTES 类标识符（下划线衔接）不匹配。
    pattern: /(?<![A-Za-z0-9])M[0-9](?:\.[0-9])?(?=\s*[\p{Script=Han}（(])/u,
    message: "注释禁止里程碑/路线图编号引用（M[0-9]）：改为能力状态语义（规划中/已接入）",
  },
  {
    // 评审/冒烟运行与波次引用；域内重试/扫描轮次语义用「段/遍/首次」表述。
    pattern: /browser-smoke|wave[0-9]|波次|首轮|二轮|三轮|第\s*[一二三四五N]\s*轮/u,
    message: "注释禁止评审/冒烟运行与波次引用：写问题与结论本身，不写发现过程",
  },
  {
    // 四位批次号形态（0232 等）：三位 0XX 模式的补漏位。
    pattern: /(?<![0-9.\-_])0[0-9]{3}(?=\s*[\p{Script=Han}（(\[])/u,
    message: "注释禁止批次号引用（0XXX 形态）",
  },
  {
    // mock 对照类：与演示层/前端/后端的契约对照属过程性旁白；演示层自身的
    // 模式互斥、三源归一、同构应答是设计事实，靠「一致/同款」后缀限定排除。
    pattern:
      /mock ?漂移|mock ?同款|(引擎|前端|后端|服务端|错误码) ?\/ ?mock|mock ?\/ ?(前端|后端|服务端|API|错误码)|与 ?mock\s*[^。\n]{0,20}(一致|同款|同文案|同规则|同版本|同语义|对齐)/u,
    message: "注释禁止 mock/前端对照类过程性表述：直接写设计事实，不写与哪层契约一致",
  },
  {
    // 风险披露旁白：行为限制写成设计事实（如「移动不生成 tombstone，客户端按版本落差重拉」）。
    pattern: /已知限制|如实标(注|述)|登记(在|于)|已在.{0,12}登记/u,
    message: "注释禁止风险披露旁白（已知限制/如实标注/登记）：改述行为与设计理由",
  },
  {
    // gitignored 引用扩写：GAP 台账/API 章节「已登记」式披露与 docs/ 指针都属指示来源；
    // 仓内契约指针（DATA-MODEL §/PRD §/API.md §）仍保留。
    pattern: /见 ?GAP|已在 ?API ?§|文档化到 ?API ?§|见 ?docs\//u,
    message: "注释禁止 GAP/API §/docs/ 登记式引用：直接写设计事实",
  },
  {
    // 阶段词：计划口吻（后续接入/先给入口）；「端点就绪后」限定端点前缀，
    // 不误伤 Spring 容器就绪等生命周期事实。
    pattern: /后续接入|端点就绪后|先给入口/u,
    message: "注释禁止阶段词（后续接入/端点就绪后/先给入口）：写当前行为与设计事实",
  },
];

/** [AC-x] 测试标签：匹配前从注释文本中剥除，其余文本照常受检。 */
const AC_TAG = /\[AC-\d+\]/gu;

/**
 * 测试标题批次前缀模式。标题是字符串字面量：注释提取器刻意跳过字符串、Checkstyle
 * IllegalTokenText 只作用于 COMMENT_CONTENT token，两层都看不到标题，故单独用
 * 「调用点 + 首参」正则兜底（it/test/describe 的第一个字符串参数、Java @DisplayName）。
 */
const WEB_TITLE_PREFIX = /\b(?:it|test|describe)\s*\(\s*(["'`])\s*0[0-9]{2,3}[A-Z]?\s/u;
const JAVA_TITLE_PREFIX = /@DisplayName\(\s*"(?:0[0-9]{2}[A-Z]?\s|0[0-9]{3}\s)/u;

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

/**
 * 测试标题批次前缀扫描：逐行匹配 it/test/describe 首参与 @DisplayName 的 `0XX ` 前缀。
 *
 * @param {string} file 文件绝对路径（用于报告）
 * @param {string} source 源码文本
 * @param {RegExp} pattern 标题前缀模式
 * @returns {string[]} 违规描述列表
 */
function scanTitlePrefix(file, source, pattern) {
  const found = [];
  source.split("\n").forEach((line, index) => {
    const match = line.match(pattern);
    if (match) {
      found.push(`${relative(repoRoot, file)}:${index + 1}: 测试标题禁止批次号前缀（0XX 形态）——命中「${match[0]}」`);
    }
  });
  return found;
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

// 标题层（web 测试标题 + Java @DisplayName）：注释两层扫不到字符串，单独兜底。
for (const file of collectFiles(WEB_SRC, [".ts", ".tsx"])) {
  violations.push(...scanTitlePrefix(file, readFileSync(file, "utf8"), WEB_TITLE_PREFIX));
}
for (const file of collectFiles(join(repoRoot, "server", "src"), [".java"])) {
  violations.push(...scanTitlePrefix(file, readFileSync(file, "utf8"), JAVA_TITLE_PREFIX));
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
