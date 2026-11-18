import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { resetApiState, setAccessToken } from "@/lib/api";
import { APPEAL_TIMEOUT_MS, appealAnnotation } from "@/lib/api/practice";

/**
 * 申诉提交超时窗口：申诉落库后服务端**同步**调 LLM 初裁（15s + 解析失败重试
 * 一次 ≈ 31.5s），与复核链路同构对齐——默认 10s 会在初裁仍进行时先把
 * 用户侧掐断，而申诉行此时已落库，重试只会撞 40900。这里用假时钟实证 10s 不中断、35s 才
 * abort，锁死 APPEAL_TIMEOUT_MS 下限（与 ask-timeout.test.ts 同构）。
 */

describe("appealAnnotation 超时窗口", () => {
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

  it("029 AC-3: 服务端初裁链路内（10s）不掐断，到 35s 才以超时中止", async () => {
    let aborted = false;
    stubHangingFetch(() => {
      aborted = true;
    });

    const pending = appealAnnotation("3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0002", "slot:1", {
      proposedAnswer: "You should pay attention to details.",
    });
    // 用 expect().rejects 挂上断言避免未处理 rejection，再推时钟。
    const assertion = expect(pending).rejects.toThrow("请求超时");

    await vi.advanceTimersByTimeAsync(10_000);
    // 默认 10s 超时若仍在生效，这里已 abort——锁死「10s 不够」的修复本身。
    expect(aborted).toBe(false);

    await vi.advanceTimersByTimeAsync(APPEAL_TIMEOUT_MS - 10_000);
    expect(aborted).toBe(true);
    await assertion;
  });
});
