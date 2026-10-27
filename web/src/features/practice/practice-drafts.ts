/**
 * 草稿的本地持久化：作答途中刷新或标签页崩溃后能恢复已输入的内容。
 * 草稿是短命的——提交成功后立刻清除，页内导航离开时由组件卸载清理；
 * 只有进程被直接杀掉（刷新/崩溃不触发 React 卸载）时草稿才会留存到下一次进入。
 */
const DRAFT_PREFIX = "yilian.drafts.";

/** 单句草稿：填空按语块下标存槽值，整句存整段文本。 */
export interface SentenceDraft {
  slots: Record<number, string>;
  free: string;
}

/**
 * 读取某句的草稿；损坏或缺失时返回空草稿，不让练习页崩溃。
 * @param sentenceId 句子 id
 * @returns 草稿（可能为空）
 */
export function loadDraft(sentenceId: string): SentenceDraft {
  const empty: SentenceDraft = { slots: {}, free: "" };
  if (typeof localStorage === "undefined") {
    return empty;
  }
  try {
    const raw = localStorage.getItem(`${DRAFT_PREFIX}${sentenceId}`);
    if (!raw) {
      return empty;
    }
    const parsed = JSON.parse(raw) as Partial<SentenceDraft> | null;
    if (!parsed || typeof parsed !== "object") {
      return empty;
    }
    const slots: Record<number, string> = {};
    for (const [key, value] of Object.entries(parsed.slots ?? {})) {
      const index = Number(key);
      if (Number.isInteger(index) && index >= 0 && typeof value === "string") {
        slots[index] = value;
      }
    }
    return { slots, free: typeof parsed.free === "string" ? parsed.free : "" };
  } catch {
    return empty;
  }
}

/**
 * 实时写入某句的草稿；隐私模式或配额不足时静默失败（草稿是锦上添花，不值得为它报错）。
 * @param sentenceId 句子 id
 * @param draft 最新草稿
 */
export function saveDraft(sentenceId: string, draft: SentenceDraft): void {
  if (typeof localStorage === "undefined") {
    return;
  }
  try {
    localStorage.setItem(`${DRAFT_PREFIX}${sentenceId}`, JSON.stringify(draft));
  } catch {
    // 存不进去就放弃；主链路（作答与提交）不依赖草稿。
  }
}

/**
 * 清除某句的草稿；离开该句或提交成功后调用，避免旧草稿污染下一次作答。
 * @param sentenceId 句子 id
 */
export function clearDraft(sentenceId: string): void {
  if (typeof localStorage === "undefined") {
    return;
  }
  try {
    localStorage.removeItem(`${DRAFT_PREFIX}${sentenceId}`);
  } catch {
    // 清不掉也无害：下一次加载会被空草稿覆盖。
  }
}
