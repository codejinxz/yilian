package io.github.codejinxz.yilian.common.logging;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 未预期异常落库入口。
 *
 * <p>只应由全局异常出口的未知异常分支调用；业务异常、参数校验、404/405/415、越权与 CSRF 都属于已知结果，不应写入 {@code
 * error_log}。实现必须自行吞掉持久化失败，不能反向影响 原始错误响应。
 */
public interface ErrorLogRecorder {

    /**
     * 记录一次未预期异常。
     *
     * @param throwable 原始异常，不能为 null
     * @param request 当前请求，允许为 null（非 Web 线程或测试场景）
     */
    void record(Throwable throwable, HttpServletRequest request);
}
