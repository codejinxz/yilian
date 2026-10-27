import type { z } from "zod";
import { apiRequest } from "@/lib/api";
import { ApiError } from "@/lib/api-error";

const FALLBACK_ERROR_CODE = -1;

/**
 * 校验服务端响应；结构不合法时抛出统一 ApiError，避免脏数据进入 Query 缓存。
 * @param schema 响应 schema
 * @param data 信封中的 data
 * @returns 通过校验的数据
 * @throws ApiError 数据结构不符合 schema
 */
export function parseData<T>(schema: z.ZodType<T>, data: unknown): T {
  const parsed = schema.safeParse(data);
  if (!parsed.success) {
    throw new ApiError(0, FALLBACK_ERROR_CODE, "服务响应异常，请稍后重试");
  }
  return parsed.data;
}

/**
 * 把查询参数拼成 `?a=1&b=2`；忽略 null/undefined/空串，避免后端收到空筛选。
 * @param values 查询参数
 * @returns 以 ? 开头的查询串；没有参数时为空串
 */
export function toQueryString(
  values: Record<string, string | number | boolean | null | undefined>,
): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value === undefined || value === null || value === "") {
      continue;
    }
    params.set(key, String(value));
  }
  const serialized = params.toString();
  return serialized ? `?${serialized}` : "";
}

/**
 * 以 JSON 方式发起写请求。
 * @param path 路径
 * @param method HTTP 方法
 * @param body 请求体
 * @returns 解包后的 data
 */
export function jsonRequest<T>(
  path: string,
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  body?: unknown,
): Promise<T> {
  return apiRequest<T>(path, {
    method,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}
