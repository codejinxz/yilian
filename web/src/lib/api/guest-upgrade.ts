import { isGuestSession } from "@/lib/api";
import { ApiError } from "@/lib/api-error";
import { listAttempts } from "@/lib/local-engine";
import { isMockEnabled } from "@/lib/mock-flag";
import type { AttemptRequest } from "@/lib/schemas/practice";
import { jsonRequest } from "./shared";

/**
 * 游客本机记录迁移（API §4.8）：注册/登录成功后把 IndexedDB 里的 StoredAttempt 批量
 * 提交到账号（≤100 条/批，clientCreatedAt 升序，RETRY 的原始提交先于改错入库）。
 *
 * 失败语义（切片 §5.2）：任何失败都保留本地数据（IndexedDB 不清理，可导出可重试）；
 * 40402（题目已不在题库，如定级自造句）或 40903（载荷冲突）跳过该批继续后续批，
 * 网络失败立即中止。
 *
 * 完成标记是增量语义（browser-smoke-wave4 缺陷修复）：记录「已成功迁入服务端的
 * attemptId 集合」而不是布尔 done——标记之后登出又练的新记录不在集合内，下次登录的
 * 自动迁移仍会捕获；跳过批的行不进集合记为欠账，下次迁移仍会重试。
 */

/** 迁移增量标记（任务书指定键名）；值为 JSON `{ migratedIds: string[] }`，只记已成功迁入的 attemptId。 */
const GUEST_UPGRADED_KEY = "yilian.guest-upgraded.v1";
/** 未同步数量状态；迁移有跳过/失败批时写入，全部成功后清除。 */
const GUEST_UPGRADE_STATE_KEY = "yilian.guest-upgrade.v1";
/** 单批上限（服务端 @Size(max=100) 对齐）。 */
const BATCH_SIZE = 100;

/** 一次迁移的结果；unsynced 为未能迁入服务端的本机记录数（仍保留在本机，下次迁移重试）。 */
export interface GuestUpgradeResult {
  imported: number;
  skipped: number;
  unsynced: number;
}

/** 设置页展示用的迁移状态快照；入口显隐由「存在未迁移记录」决定，不再有布尔完成态。 */
export interface GuestUpgradeState {
  /** 已成功迁入服务端的 attemptId 集合；不在集合内的本机记录都视为待迁移。 */
  migratedIds: string[];
  /** 最近一次迁移未同步的条数；0 表示没有待重试的欠账。 */
  unsynced: number;
}

/**
 * 读取已迁移 id 集合；标记不存在、损坏或为旧版布尔值（历史版本写过 "done"/deviceId 字符串）
 * 时按空集合降级——旧标记持有者会整批重发一次，服务端按 attemptId 同载荷幂等 skip
 * 不产生重复，重发完成后即写入新格式，一次性代价。
 * @returns 已迁移 attemptId 数组
 */
function readMigratedIds(): string[] {
  try {
    const raw = window.localStorage.getItem(GUEST_UPGRADED_KEY);
    if (raw == null) {
      return [];
    }
    const parsed: unknown = JSON.parse(raw);
    if (parsed !== null && typeof parsed === "object") {
      const ids = (parsed as { migratedIds?: unknown }).migratedIds;
      if (Array.isArray(ids)) {
        return ids.filter((id): id is string => typeof id === "string");
      }
    }
    return [];
  } catch {
    // 旧版布尔标记（如 "done"）不是合法 JSON，走这里：按空集合降级，见函数注释。
    return [];
  }
}

/**
 * 读取未同步欠账数量；状态键不存在或损坏时按 0 处理。
 * @returns 欠账条数
 */
function readUnsynced(): number {
  try {
    const parsed: unknown = JSON.parse(
      window.localStorage.getItem(GUEST_UPGRADE_STATE_KEY) ?? "{}",
    );
    const value =
      parsed !== null && typeof parsed === "object"
        ? Number((parsed as { unsynced?: unknown }).unsynced)
        : 0;
    return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
  } catch {
    return 0;
  }
}

/**
 * 读取迁移状态快照；localStorage 不可用时按「空集合、无欠账」降级。
 * @returns 状态快照
 */
export function readGuestUpgradeState(): GuestUpgradeState {
  try {
    return { migratedIds: readMigratedIds(), unsynced: readUnsynced() };
  } catch {
    return { migratedIds: [], unsynced: 0 };
  }
}

/**
 * 过滤出尚未迁入服务端的本机记录：attemptId 不在已迁移集合内的才需要同步。
 * 增量语义的核心：完成标记之后新产生的记录不会被挡住；重试只重发欠账部分，
 * 已迁移 id 不重发（服务端幂等可兜底，但没必要白走网络）。
 * @param attempts 本机答题记录
 * @param migratedIds 已迁移 attemptId 集合（readGuestUpgradeState().migratedIds）
 * @returns 未迁移的记录子集（保持原顺序）
 */
export function filterUnmigratedAttempts<T extends { attemptId: string }>(
  attempts: readonly T[],
  migratedIds: readonly string[],
): T[] {
  const migrated = new Set(migratedIds);
  return attempts.filter((attempt) => !migrated.has(attempt.attemptId));
}

/**
 * 把本轮确认成功的 attemptId 合并进增量标记（去重）；失败批的行不写入，欠账保留。
 * localStorage 不可用时静默：迁移本身已成功，下次登录会重发这些记录，
 * 服务端按 attemptId 同载荷幂等 skip，不产生重复。
 * @param ids 本轮成功迁入的 attemptId
 */
function addToMigrated(ids: readonly string[]): void {
  if (ids.length === 0) {
    return;
  }
  try {
    const merged = Array.from(new Set([...readMigratedIds(), ...ids]));
    window.localStorage.setItem(GUEST_UPGRADED_KEY, JSON.stringify({ migratedIds: merged }));
  } catch {
    // localStorage 被禁用时静默：只影响下次是否重发，不影响本次迁移结果。
  }
}

/**
 * 记录未同步数量；供设置页提示「部分本地记录未同步，可重试」，全部成功后清除。
 * @param unsynced 未同步条数
 */
function saveUnsynced(unsynced: number): void {
  try {
    if (unsynced > 0) {
      window.localStorage.setItem(GUEST_UPGRADE_STATE_KEY, JSON.stringify({ unsynced }));
    } else {
      window.localStorage.removeItem(GUEST_UPGRADE_STATE_KEY);
    }
  } catch {
    // localStorage 被禁用时静默：设置页只是少一条提示，迁移重试入口仍然可用。
  }
}

/**
 * 按提交时间升序排序：RETRY 携带的 retryOfId 指向同用户/题/模式的原始提交，
 * 原始提交先入库才能通过服务端对改错链的校验。
 * @param attempts 本机答题记录
 * @returns 升序排列的提交载荷
 */
function attemptsInOrder(attempts: Awaited<ReturnType<typeof listAttempts>>): AttemptRequest[] {
  return [...attempts]
    .sort((a, b) =>
      (a.request.clientCreatedAt ?? a.createdAt).localeCompare(
        b.request.clientCreatedAt ?? b.createdAt,
      ),
    )
    .map((attempt) => attempt.request);
}

/**
 * 同步本机练习记录到当前登录账号；设置页手动入口与登录/注册成功后的自动触发共用。
 * 幂等可重入：只发送「未迁移」的记录（attemptId 不在增量标记内），成功批记入标记
 * 不重发，失败批保留为欠账下次重试；服务端对同 attemptId 同载荷幂等 skip 兜底，
 * 不会产生重复记录。
 * @returns 迁移结果；未登录、mock 模式或没有未迁移记录时返回全零且不发请求
 * @throws Error 本地引擎不可用（IndexedDB 缺失且无测试注入）时由引擎统一抛出
 */
export async function syncGuestAttempts(): Promise<GuestUpgradeResult> {
  if (isMockEnabled() || isGuestSession()) {
    return { imported: 0, skipped: 0, unsynced: 0 };
  }
  const unmigrated = filterUnmigratedAttempts(
    await listAttempts(),
    readGuestUpgradeState().migratedIds,
  );
  if (unmigrated.length === 0) {
    // 没有未迁移记录：不打标记不发请求。增量语义下每次登录要读一次 IndexedDB 才能发现
    // 新记录，本地读取代价可接受，换来「完成后新练的记录仍能迁移」。
    return { imported: 0, skipped: 0, unsynced: 0 };
  }
  const requests = attemptsInOrder(unmigrated);

  let imported = 0;
  let skipped = 0;
  let unsynced = 0;
  let aborted = false;
  const migratedInRun: string[] = [];
  for (let start = 0; start < requests.length; start += BATCH_SIZE) {
    const batch = requests.slice(start, start + BATCH_SIZE);
    if (aborted) {
      unsynced += batch.length;
      continue;
    }
    try {
      const result = (await jsonRequest<{ imported?: number; skipped?: number }>(
        "/auth/guest-upgrade",
        "POST",
        { attempts: batch },
      )) as { imported?: number; skipped?: number };
      imported += result.imported ?? 0;
      skipped += result.skipped ?? 0;
      // 只把确认成功的批记入增量标记；失败批的行留在欠账里，下次迁移仍会尝试。
      migratedInRun.push(...batch.map((attempt) => attempt.attemptId));
    } catch (error) {
      if (error instanceof ApiError && error.status === 0) {
        // 网络失败：后续批同样发不出去，立即中止并把剩余全部记为未同步。
        aborted = true;
        unsynced += batch.length;
        continue;
      }
      // 业务失败（40402 题目不在题库 / 40903 载荷冲突等）：跳过该批继续后续批，
      // 这些行下次迁移仍会重试（欠账语义，决策登记 docs/retro/friction-wave4.md）。
      unsynced += batch.length;
    }
  }

  addToMigrated(migratedInRun);
  saveUnsynced(unsynced);
  return { imported, skipped, unsynced };
}

/** 自动迁移结果事件名；AppShell 监听后以系统条提示。手动同步的反馈由设置页页内 toast 负责。 */
export const GUEST_UPGRADE_SYNCED_EVENT = "yilian:guest-upgrade-synced";

/** 自动迁移结果事件携带的明细。 */
export interface GuestUpgradeSyncedDetail {
  /** 本次新迁入服务端的记录数。 */
  imported: number;
  /** 未能迁入的记录数（仍保留在本机，可在设置页重试）。 */
  unsynced: number;
}

/**
 * 登录/注册成功后的自动迁移入口：fire-and-forget，不阻塞跳转；
 * 有实际迁移结果时派发 GUEST_UPGRADE_SYNCED_EVENT（冒烟发现迁移成功无任何反馈），
 * 失败欠账由设置页的提示与手动重试入口兜底。
 * @returns 迁移完成的 Promise（调用方通常 void 掉）
 */
export function syncGuestAttemptsInBackground(): Promise<GuestUpgradeResult> {
  return syncGuestAttempts().then((result) => {
    if (result.imported > 0 || result.unsynced > 0) {
      try {
        window.dispatchEvent(
          new CustomEvent<GuestUpgradeSyncedDetail>(GUEST_UPGRADE_SYNCED_EVENT, {
            detail: { imported: result.imported, unsynced: result.unsynced },
          }),
        );
      } catch {
        // 事件派发失败只影响提示，不影响迁移结果本身。
      }
    }
    return result;
  });
}
