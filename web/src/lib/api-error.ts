/**
 * 统一的 API 异常：status 是 HTTP 状态，code 是后端 ApiCode 业务码。
 * 单独放在此文件，mock adapter 与请求客户端共享，避免循环依赖。
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: number;

  /**
   * 创建可直接展示给用户的 API 异常。
   * @param status HTTP 状态码；网络错误或超时为 0
   * @param code 后端业务码；缺失时用 -1 兜底
   * @param message 可直接展示的错误提示
   */
  constructor(status: number, code: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}
