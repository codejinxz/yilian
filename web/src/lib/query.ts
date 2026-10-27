import { QueryClient, queryOptions } from "@tanstack/react-query";
import { ApiError, api, type CurrentUser } from "@/lib/api";
import { getSettings } from "@/lib/api/account";
import { getToday } from "@/lib/api/practice";
import type { UserSettings } from "@/lib/schemas/account";
import type { Today } from "@/lib/schemas/practice";

/**
 * 统一的 Query key 工厂；所有失效逻辑从这里取 key，避免页面各自拼字符串产生孤儿缓存。
 */
export const queryKeys = {
  me: ["auth", "me"] as const,
  settings: ["me", "settings"] as const,
  today: ["practice", "today"] as const,
  nextPractice: (params: {
    bankId?: string;
    mode?: "CLOZE" | "FREE";
    sentenceId?: string;
    tag?: string;
    excludeIds?: string[];
    excludeSentenceId?: string;
  }) => ["practice", "next", params] as const,
  attempt: (attemptId: string) => ["practice", "attempt", attemptId] as const,
  reviews: (params: {
    page?: number;
    size?: number;
    state?: "NEW" | "LEARNING" | "MASTERED";
    mode?: "CLOZE" | "FREE";
    due?: boolean;
  }) => ["practice", "reviews", params] as const,
  wrongQuestions: (params: Record<string, unknown>) =>
    ["practice", "wrong-questions", params] as const,
  accuracy: (params: { from: string; to: string }) => ["analytics", "accuracy", params] as const,
  errorTags: (params: { from: string; to: string }) => ["analytics", "error-tags", params] as const,
  admin: {
    users: (params: Record<string, unknown>) => ["admin", "users", params] as const,
    auditLogs: (params: Record<string, unknown>) => ["admin", "audit-logs", params] as const,
    errorLogs: (params: Record<string, unknown>) => ["admin", "error-logs", params] as const,
    errorLog: (id: string) => ["admin", "error-logs", id] as const,
    banks: (params: Record<string, unknown>) => ["admin", "banks", params] as const,
    bankSentences: (bankId: string, params: Record<string, unknown>) =>
      ["admin", "banks", bankId, "sentences", params] as const,
    imports: (params: Record<string, unknown>) => ["admin", "imports", params] as const,
    importBatch: (batchId: string, params: Record<string, unknown>) =>
      ["admin", "imports", batchId, params] as const,
  },
};

/**
 * 当前用户查询；网络错误与 5xx 只重试一次，401/403 不重试。
 */
export const meQueryOptions = queryOptions<CurrentUser>({
  queryKey: queryKeys.me,
  queryFn: ({ signal }) => api.me(signal),
  staleTime: 30_000,
  retry: (failureCount, error) =>
    failureCount < 1 && error instanceof ApiError && (error.status === 0 || error.status >= 500),
});

/**
 * 用户设置查询；主题、每日目标等都在这里，Zustand 只保存本地主题偏好。
 */
export const settingsQueryOptions = queryOptions<UserSettings>({
  queryKey: queryKeys.settings,
  queryFn: ({ signal }) => getSettings(signal),
  staleTime: 60_000,
  retry: (failureCount, error) =>
    failureCount < 1 && error instanceof ApiError && (error.status === 0 || error.status >= 500),
});

/**
 * 今日启动台查询；窗口重新聚焦时刷新，跨过本地日界后能拿到新数字。
 */
export const todayQueryOptions = queryOptions<Today>({
  queryKey: queryKeys.today,
  queryFn: ({ signal }) => getToday(signal),
  staleTime: 15_000,
  refetchOnWindowFocus: true,
  retry: (failureCount, error) =>
    failureCount < 1 && error instanceof ApiError && (error.status === 0 || error.status >= 500),
});

/**
 * 创建应用级 QueryClient：查询只对网络错误和 5xx 重试一次，mutation 不自动重试，避免重复提交。
 * @returns 配置好的 QueryClient
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failureCount, error) =>
          failureCount < 1 &&
          (!(error instanceof ApiError) || error.status === 0 || error.status >= 500),
        refetchOnWindowFocus: false,
        staleTime: 30_000,
      },
      mutations: {
        retry: 0,
      },
    },
  });
}
