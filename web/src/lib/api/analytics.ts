import { apiRequest } from "@/lib/api";
import {
  type Accuracy,
  accuracySchema,
  type ErrorTags,
  errorTagsSchema,
} from "@/lib/schemas/analytics";
import { parseData, toQueryString } from "./shared";

/**
 * 读取区间正确率。
 * @param params from/to 本地日期与可选筛选
 * @param signal 取消信号
 * @returns 正确率与分模式数据
 */
export async function getAccuracy(
  params: { from: string; to: string; bankId?: string; mode?: "CLOZE" | "FREE" },
  signal?: AbortSignal,
): Promise<Accuracy> {
  const data = await apiRequest<unknown>(`/analytics/accuracy${toQueryString(params)}`, { signal });
  return parseData(accuracySchema, data);
}

/**
 * 读取错误类型分布；已 DISPUTE 的批注不计。
 * @param params from/to 本地日期
 * @param signal 取消信号
 * @returns 考点计数与占比
 */
export async function getErrorTags(
  params: { from: string; to: string },
  signal?: AbortSignal,
): Promise<ErrorTags> {
  const data = await apiRequest<unknown>(`/analytics/error-tags${toQueryString(params)}`, {
    signal,
  });
  return parseData(errorTagsSchema, data);
}
