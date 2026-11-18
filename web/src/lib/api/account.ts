import { ApiError, apiRequest, getAccessToken } from "@/lib/api";
import { isMockEnabled } from "@/lib/mock-flag";
import {
  type AccountExport,
  accountExportSchema,
  type DeleteAccountReceipt,
  type DeleteAccountRequest,
  deleteAccountReceiptSchema,
  deleteAccountRequestSchema,
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

/**
 * 自助删号：密码（与 2FA 动态码）确认后软删账号并吊销全部会话。
 * 成功后本会话立即失效，调用方负责清会话回登录页。
 * @param input 当前密码；开启两步验证时另附 8 位动态码或恢复码
 * @returns 回执（软删时刻与匿名化时点）
 * @throws ApiError 密码或动态码错误（40101）、已删重复提交（40900）、限流或网络失败
 */
export async function deleteAccount(input: DeleteAccountRequest): Promise<DeleteAccountReceipt> {
  const parsed = deleteAccountRequestSchema.safeParse(input);
  if (!parsed.success) {
    throw new ApiError(0, 40000, parsed.error.issues[0]?.message ?? "请求参数不合法");
  }
  const data = await jsonRequest<unknown>("/auth/delete-account", "POST", parsed.data);
  return parseData(deleteAccountReceiptSchema, data);
}

/** 导出请求超时；整包 JSON 单人量级小，但移动端弱网放宽到 30 秒。 */
const EXPORT_TIMEOUT_MS = 30_000;

/**
 * 服务端账号数据导出：GET /api/me/export。
 *
 * 响应体是导出 JSON 本身（带 Content-Disposition 附件）而非统一信封，因此不走
 * {@link apiRequest} 的信封解包，单独 fetch；mock 分支复用 handlers 的同形应答。
 * @param signal TanStack Query 取消信号（可选）
 * @returns 已校验的导出对象（调用方负责转 Blob 下载落盘）
 * @throws ApiError 未登录（401）、响应结构异常或网络失败
 */
export async function exportAccountData(signal?: AbortSignal): Promise<AccountExport> {
  if (isMockEnabled()) {
    const { handleMockRequest } = await import("@/mocks/handlers");
    const data = await handleMockRequest("/me/export", { signal });
    return parseExport(data);
  }
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), EXPORT_TIMEOUT_MS);
  signal?.addEventListener("abort", () => controller.abort(), { once: true });
  const token = getAccessToken();
  let response: Response;
  try {
    response = await fetch("/api/me/export", {
      credentials: "include",
      headers: {
        Accept: "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      signal: controller.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new ApiError(0, -1, "导出超时，请重试", "transport");
    }
    throw new ApiError(0, -1, "网络连接失败，请检查网络后重试", "transport");
  } finally {
    window.clearTimeout(timeoutId);
  }
  if (!response.ok) {
    throw await toExportError(response);
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new ApiError(response.status, -1, "服务响应异常，请稍后重试", "response");
  }
  return parseExport(body);
}

/**
 * 解析并校验导出 JSON；结构不合法按服务端响应异常处理，避免把坏文件落盘。
 * @param body 导出 JSON
 * @returns 已校验的导出对象
 * @throws ApiError 结构不符合 accountExportSchema
 */
function parseExport(body: unknown): AccountExport {
  const parsed = accountExportSchema.safeParse(body);
  if (!parsed.success) {
    throw new ApiError(0, -1, "服务响应异常，请稍后重试", "response");
  }
  return parsed.data;
}

/**
 * 把导出失败响应转换为 ApiError；尽力读取统一失败信封的中文 message。
 * @param response 失败响应
 * @returns 带 HTTP 状态与业务码的异常
 */
async function toExportError(response: Response): Promise<ApiError> {
  try {
    const envelope = (await response.json()) as { code?: number; message?: string };
    return new ApiError(
      response.status,
      envelope.code ?? response.status,
      envelope.message ?? "导出失败，请稍后重试",
    );
  } catch {
    return new ApiError(response.status, response.status, "导出失败，请稍后重试");
  }
}
