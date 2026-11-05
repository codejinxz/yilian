import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { resetApiState, setAccessToken } from "@/lib/api";
import { ASK_TIMEOUT_MS, askTutor } from "@/lib/api/practice";

/**
 * 答疑请求超时窗口（服务端降级链路 31.5s）：模型调用 15s + 网关 429 冷却退避 30s
 * 重试一次后服务端才有最终答复——前端默认 10s 会在服务端仍重试时先行掐断，用户永远
 * 只看到超时。这里用假时钟实证 10s 不中断、35s 才 abort，锁死 ASK_TIMEOUT_MS 下限。
 */

describe("askTutor 超时窗口", () => {
  beforeEach(() => {
    resetApiState();
    // 持有 access token 让 /practice/* 走网络而不是游客本地引擎；POST 需要可读 CSRF cookie。
    setAccessToken("access-token");
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要预置可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  /** 挂起的 fetch 桩：只有收到 abort 才以 signal.reason 拒绝，用于观测超时触发时机。 */
  function stubHangingFetch(onAbort: () => void): void {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_input: RequestInfo | URL, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () => {
              onAbort();
              reject(init.signal?.reason);
            });
          }),
      ),
    );
  }

  it("023 AC-1: 服务端降级链路内（10s）不掐断，到 35s 才以超时中止", async () => {
    let aborted = false;
    stubHangingFetch(() => {
      aborted = true;
    });

    const pending = askTutor("01HSENTENCE", "这里介词为什么用 to？");
    // 用 expect().rejects 挂上断言避免未处理 rejection，再推时钟。
    const assertion = expect(pending).rejects.toThrow("请求超时");

    await vi.advanceTimersByTimeAsync(10_000);
    // 默认 10s 超时若仍在生效，这里已 abort——锁死「10s 不够」的修复本身。
    expect(aborted).toBe(false);

    await vi.advanceTimersByTimeAsync(ASK_TIMEOUT_MS - 10_000);
    expect(aborted).toBe(true);
    await assertion;
  });
});
