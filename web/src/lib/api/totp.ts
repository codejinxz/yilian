import { apiRequest } from "@/lib/api";
import {
  type DisableTotpRequest,
  type TotpActivation,
  type TotpSetup,
  type TotpStatus,
  totpActivationSchema,
  totpSetupSchema,
  totpStatusSchema,
} from "@/lib/schemas/account";
import { jsonRequest, parseData } from "./shared";

/**
 * 读取当前用户两步验证状态；响应不含任何密钥材料。
 * @param signal TanStack Query 取消信号
 * @returns enabled / enrolledAt / 剩余恢复码数
 */
export async function getTotpStatus(signal?: AbortSignal): Promise<TotpStatus> {
  const data = await apiRequest<unknown>("/auth/2fa/status", { signal });
  return parseData(totpStatusSchema, data);
}

/**
 * 注册第一步：密码确认后生成新密钥；Base32 密钥与 otpauth URI 仅本次响应可见。
 * @param password 当前登录密码
 * @returns 密钥与 otpauth URI 文本
 */
export async function setupTotp(password: string): Promise<TotpSetup> {
  const data = await jsonRequest<unknown>("/auth/2fa/setup", "POST", { password });
  return parseData(totpSetupSchema, data);
}

/**
 * 注册第二步：验证器当前动态码通过后启用并签发一次性恢复码。
 * @param code 验证器当前 8 位动态码
 * @returns 10 个恢复码明文（仅此一次）
 */
export async function activateTotp(code: string): Promise<TotpActivation> {
  const data = await jsonRequest<unknown>("/auth/2fa/activate", "POST", { code });
  return parseData(totpActivationSchema, data);
}

/**
 * 关闭两步验证：密码 + 当前动态码（或恢复码）双确认。
 * @param input 密码与验证码
 */
export async function disableTotp(input: DisableTotpRequest): Promise<void> {
  await jsonRequest<unknown>("/auth/2fa/disable", "POST", input);
}
