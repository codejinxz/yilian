import { apiRequest } from "@/lib/api";
import {
  settingsSchema,
  type UpdateSettingsRequest,
  type UserSettings,
} from "@/lib/schemas/account";
import { jsonRequest, parseData } from "./shared";

/**
 * 读取当前用户设置。
 * @param signal TanStack Query 取消信号
 * @returns 已校验的设置
 */
export async function getSettings(signal?: AbortSignal): Promise<UserSettings> {
  const data = await apiRequest<unknown>("/me/settings", { signal });
  return parseData(settingsSchema, data);
}

/**
 * 更新设置；后端返回完整设置，页面据此刷新 Query 缓存。
 * @param input 至少一个待更新字段
 * @returns 更新后的完整设置
 */
export async function updateSettings(input: UpdateSettingsRequest): Promise<UserSettings> {
  const data = await jsonRequest<unknown>("/me/settings", "PUT", input);
  return parseData(settingsSchema, data);
}
