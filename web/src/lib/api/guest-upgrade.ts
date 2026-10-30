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
 * 网络失败立即中止；未同步数量落 localStorage 供设置页提示，全部成功后写完成标记不再重发。
 */

/** 迁移完成标记（任务书指定键名）；存在即不再重发。 */
const GUEST_UPGRADED_KEY = "yilian.guest-upgraded.v1";
/** 未同步数量状态；迁移有跳过/失败批时写入，全部成功后清除。 */
const GUEST_UPGRADE_STATE_KEY = "yilian.guest-upgrade.v1";
/** 单批上限（服务端 @Size(max=100) 对齐）。 */
const BATCH_SIZE = 100;

/** 一次迁移的结果；unsynced 为未能迁入服务端的本机记录数（仍保留在本机）。 */
export interface GuestUpgradeResult {
  imported: number;
  skipped: number;
  unsynced: number;
}

/** 设置页展示用的迁移状态快照。 */
export interface GuestUpgradeState {
  /** 迁移是否已完成（完成标记存在）；完成后不再提供重试入口。 */
  done: boolean;
  /** 最近一次迁移未同步的条数；0 表示没有待重试的欠账。 */
  unsynced: number;
}

/**
 * 读取迁移状态快照；localStorage 不可用或数据损坏时按「未迁移、无欠账」降级。
 * @returns 状态快照
 */
export function readGuestUpgradeState(): GuestUpgradeState {
  try {
    return {
      done: window.localStorage.getItem(GUEST_UPGRADED_KEY) != null,
      unsynced: Number(
        JSON.parse(window.localStorage.getItem(GUEST_UPGRADE_STATE_KEY) ?? "{}")?.unsynced ?? 0,
      ),
    };
  } catch {
    return { done: false, unsynced: 0 };
  }
}

/**
 * 写迁移完成标记并清除欠账状态；只在全部批次成功（或本机没有可迁移数据）后调用。
 */
function markUpgraded(): void {
  try {
    window.localStorage.setItem(GUEST_UPGRADED_KEY, "done");
    window.localStorage.removeItem(GUEST_UPGRADE_STATE_KEY);
  } catch {
    // localStorage 被禁用时静默：迁移本身已成功，只是下次登录会重发一批幂等请求（全 skip）。
  }
}

/**
 * 记录未同步数量；供设置页提示「部分本地记录未同步，可重试」。
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
 * 幂等可重入：服务端对同 attemptId 同载荷跳过；本函数对失败批保留数据，重试时整批重发
 * （已成功的批次会被服务端 skip，不会产生重复记录）。
 * @returns 迁移结果；未登录、mock 模式或已完成迁移时返回全零且不发请求
 * @throws Error 本地引擎不可用（IndexedDB 缺失且无测试注入）时由引擎统一抛出
 */
export async function syncGuestAttempts(): Promise<GuestUpgradeResult> {
  if (isMockEnabled() || isGuestSession() || readGuestUpgradeState().done) {
    return { imported: 0, skipped: 0, unsynced: 0 };
  }
  const requests = attemptsInOrder(await listAttempts());
  if (requests.length === 0) {
    // 本机没有可迁移数据：直接打完成标记，避免每次登录都重读一遍 IndexedDB。
    markUpgraded();
    return { imported: 0, skipped: 0, unsynced: 0 };
  }

  let imported = 0;
  let skipped = 0;
  let unsynced = 0;
  let aborted = false;
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
    } catch (error) {
      if (error instanceof ApiError && error.status === 0) {
        // 网络失败：后续批同样发不出去，立即中止并把剩余全部记为未同步。
        aborted = true;
        unsynced += batch.length;
        continue;
      }
      // 业务失败（40402 题目不在题库 / 40903 载荷冲突等）：跳过该批继续后续批，
      // 未同步数量在设置页明确提示（决策登记 docs/retro/friction-wave4.md）。
      unsynced += batch.length;
    }
  }

  if (unsynced === 0) {
    markUpgraded();
  } else {
    saveUnsynced(unsynced);
  }
  return { imported, skipped, unsynced };
}

/**
 * 登录/注册成功后的自动迁移入口：fire-and-forget，不阻塞跳转；
 * 结果落 localStorage，失败由设置页的提示与手动重试入口兜底。
 * @returns 迁移完成的 Promise（调用方通常 void 掉）
 */
export function syncGuestAttemptsInBackground(): Promise<GuestUpgradeResult> {
  return syncGuestAttempts();
}
