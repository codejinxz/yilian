import type { QueryClient } from "@tanstack/react-query";
import { useSessionStore } from "@/features/auth/session";
import { setAccessToken } from "@/lib/api";

/** 同源标签页之间只广播“会话已清除”，不广播 access token。 */
const CHANNEL_NAME = "yilian-session";

type SessionListener = () => void;

const channel =
  typeof window !== "undefined" && typeof window.BroadcastChannel === "function"
    ? new window.BroadcastChannel(CHANNEL_NAME)
    : null;

/** 通知其他标签页清空本地会话。 */
export function notifySessionCleared(): void {
  channel?.postMessage("session-cleared");
}

/**
 * 订阅其他标签页的会话清除事件。
 * @param listener 收到事件后的回调
 * @returns 取消订阅函数
 */
export function onSessionCleared(listener: SessionListener): () => void {
  if (!channel) {
    return () => {};
  }
  const handler = () => listener();
  channel.addEventListener("message", handler);
  return () => channel.removeEventListener("message", handler);
}

/**
 * 清空内存 token、Zustand 状态和全部 Query 缓存；默认通知其他标签页。
 * @param queryClient 应用级 QueryClient
 * @param broadcast 是否广播；接收广播的一侧传 false，避免回环
 */
export function clearSession(queryClient: QueryClient, broadcast = true): void {
  setAccessToken(null);
  useSessionStore.getState().setAnonymous();
  queryClient.clear();
  if (broadcast) {
    notifySessionCleared();
  }
}
