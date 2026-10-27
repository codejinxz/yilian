import { create } from "zustand";

/** 会话的三种状态：unknown 表示冷启动后尚未确认。 */
export type SessionStatus = "unknown" | "authenticated" | "anonymous";

interface SessionState {
  status: SessionStatus;
  /** 标记为已登录；只改状态，不写入用户资料。 */
  setAuthenticated: () => void;
  /** 标记为匿名；由登出或 401 处理调用。 */
  setAnonymous: () => void;
}

/**
 * 只保存会话的粗粒度状态；用户资料由 TanStack Query 管理，access token 由 api 模块内存保存。
 */
export const useSessionStore = create<SessionState>()((set) => ({
  status: "unknown",
  setAuthenticated: () => set({ status: "authenticated" }),
  setAnonymous: () => set({ status: "anonymous" }),
}));
