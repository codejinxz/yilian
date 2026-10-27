import { apiRequest } from "@/lib/api";
import type { PageResponse } from "@/lib/schemas/common";
import {
  type BankView,
  bankPageSchema,
  bankViewSchema,
  versionedSentencesSchema,
} from "@/lib/schemas/content";
import { parseData, toQueryString } from "./shared";

/**
 * 读取公开题库 + 自己的私有题库。
 * @param params 分页、关键字、可见性与状态筛选
 * @returns 分页题库
 */
export async function getBanks(
  params: {
    page?: number;
    size?: number;
    keyword?: string;
    visibility?: "PUBLIC" | "PRIVATE";
    status?: string;
  },
  signal?: AbortSignal,
): Promise<PageResponse<BankView>> {
  const data = await apiRequest<unknown>(`/banks${toQueryString(params)}`, { signal });
  return parseData(bankPageSchema, data);
}

/**
 * 读取题库详情。
 * @param bankId 题库 id
 * @param signal 取消信号
 * @returns 题库
 */
export async function getBank(bankId: string, signal?: AbortSignal): Promise<BankView> {
  const data = await apiRequest<unknown>(`/banks/${encodeURIComponent(bankId)}`, { signal });
  return parseData(bankViewSchema, data);
}

/**
 * 按版本游标增量拉取题目；首次 sinceVersion=0。
 * @param bankId 题库 id
 * @param params sinceVersion 与 limit
 * @param signal 取消信号
 * @returns 版本化题目列表
 */
export async function getBankSentences(
  bankId: string,
  params: { sinceVersion?: number; limit?: number } = {},
  signal?: AbortSignal,
) {
  const data = await apiRequest<unknown>(
    `/banks/${encodeURIComponent(bankId)}/sentences${toQueryString(params)}`,
    { signal },
  );
  return parseData(versionedSentencesSchema, data);
}
