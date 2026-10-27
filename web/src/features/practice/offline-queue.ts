import { useEffect, useState } from "react";
import { submitAttempt } from "@/lib/api/practice";
import { ApiError } from "@/lib/api-error";
import type { AttemptRequest } from "@/lib/schemas/practice";

const STORAGE_KEY = "yilian.offline-attempts.v1";
const CHANGE_EVENT = "yilian:offline-queue";

/** 待同步的离线答卷：request 原样保存以便联网后重放，savedAt 用于展示排队时长。 */
export interface QueuedAttempt {
  request: AttemptRequest;
  savedAt: string;
}

/**
 * 读取本地待同步答卷；存储损坏时返回空数组，不让练习页崩溃。
 * @returns 待同步队列
 */
export function readQueuedAttempts(): QueuedAttempt[] {
  if (typeof localStorage === "undefined") {
    return [];
  }
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? (parsed as QueuedAttempt[]) : [];
  } catch {
    return [];
  }
}

/**
 * 写入队列并广播变更。
 * @param queue 新队列
 */
function writeQueue(queue: QueuedAttempt[]): void {
  if (typeof localStorage !== "undefined") {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(queue));
    } catch {
      // 配额或隐私模式失败时只保留内存提示，不静默丢弃用户已看到的队列。
    }
  }
  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
  }
}

/**
 * 把一次未提交成功的答卷存入本地队列；同一个 attemptId 不重复入队。
 * @param request attempt 请求
 */
export function enqueueAttempt(request: AttemptRequest): void {
  const queue = readQueuedAttempts();
  if (queue.some((item) => item.request.attemptId === request.attemptId)) {
    return;
  }
  writeQueue([...queue, { request, savedAt: new Date().toISOString() }]);
}

/**
 * 从队列移除已同步的答卷。
 * @param attemptId attempt id
 */
export function removeQueuedAttempt(attemptId: string): void {
  writeQueue(readQueuedAttempts().filter((item) => item.request.attemptId !== attemptId));
}

/**
 * 尝试同步全部本地队列；网络错误立即停止，业务冲突保留在队列并返回提示，绝不静默丢弃。
 * @returns 同步成功数与仍需处理的提示
 */
export async function flushQueuedAttempts(): Promise<{ synced: number; message: string | null }> {
  const queue = readQueuedAttempts();
  let synced = 0;
  for (const item of queue) {
    try {
      await submitAttempt(item.request);
      removeQueuedAttempt(item.request.attemptId);
      synced += 1;
    } catch (error) {
      if (error instanceof ApiError && error.status === 0) {
        return { synced, message: "仍然离线，待同步答卷已保留在本机。" };
      }
      return {
        synced,
        message:
          error instanceof Error
            ? `有一份答卷未能同步：${error.message}。它仍保留在本机，可稍后重试。`
            : "有一份答卷未能同步，它仍保留在本机。",
      };
    }
  }
  return { synced, message: null };
}

/**
 * 订阅待同步队列长度；AppShell 与练习页共用。
 * @returns 当前待同步数量
 */
export function useOfflineQueueCount(): number {
  const [count, setCount] = useState(() => readQueuedAttempts().length);
  useEffect(() => {
    const update = () => setCount(readQueuedAttempts().length);
    window.addEventListener(CHANGE_EVENT, update);
    window.addEventListener("storage", update);
    return () => {
      window.removeEventListener(CHANGE_EVENT, update);
      window.removeEventListener("storage", update);
    };
  }, []);
  return count;
}
