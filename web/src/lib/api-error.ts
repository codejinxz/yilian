/**
 * 统一的 API 异常：status 是 HTTP 状态，code 是后端 ApiCode 业务码。
 * 单独放在此文件，mock adapter 与请求客户端共享，避免循环依赖。
 */

/** status=0 时的失败细分；调用方据此区分「请求可能已到服务端」与「服务端必已处理」。 */
export type NetworkErrorKind =
  /** 传输层失败（超时/断网/连接重置）：请求可能已到达服务端，重试依赖幂等键去重。 */
  | "transport"
  /** 响应结构失败：已收到 2xx 但 body 校验不过，服务端处理必定已发生，绝不能按离线降级。 */
  | "response";

export class ApiError extends Error {
  readonly status: number;
  readonly code: number;
  /** 网络层失败细分；仅 status=0 的失败由客户端产生时填写，HTTP 状态错误不填。 */
  readonly networkKind?: NetworkErrorKind;

  /**
   * 创建可直接展示给用户的 API 异常。
   * @param status HTTP 状态码；网络错误或超时为 0
   * @param code 后端业务码；缺失时用 -1 兜底
   * @param message 可直接展示的错误提示
   * @param networkKind status=0 时的失败细分；缺省表示未分类的历史路径
   */
  constructor(status: number, code: number, message: string, networkKind?: NetworkErrorKind) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.networkKind = networkKind;
  }
}

/**
 * 判断是否为传输层失败（超时/断网/连接重置）。
 *
 * <p>014 冒烟根因：提交链路曾把一切 {@code status===0} 都当离线，而 2xx 后 body 校验失败
 * 也抛 status=0，导致「服务端已成功、前端判失败」把答卷误入离线队列再重放。
 * 只有 transport 类失败才允许走离线降级；response 类失败重试同一载荷即可自愈（幂等键去重）。
 *
 * @param error 待分类的异常
 * @returns 是否传输层失败
 */
export function isTransportError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 0 && error.networkKind === "transport";
}
