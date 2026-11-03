import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useOnlineStatus } from "@/hooks/use-online-status";

describe("useOnlineStatus 的 offline 事件防抖（014 冒烟瞬时抖动防御）", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("015 AC-4: offline 事件持续超过 1.5 秒才翻转在线态", () => {
    const { result } = renderHook(() => useOnlineStatus());
    expect(result.current).toBe(true);

    act(() => {
      window.dispatchEvent(new Event("offline"));
    });
    // 抖动窗口内状态不翻转：提交入口不会因此把答卷误入离线队列。
    act(() => {
      vi.advanceTimersByTime(1400);
    });
    expect(result.current).toBe(true);

    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(result.current).toBe(false);
  });

  it("015 AC-4: 防抖窗口内来了 online 事件则取消翻转（瞬时 offline→online 抖动不误判）", () => {
    const { result } = renderHook(() => useOnlineStatus());

    act(() => {
      window.dispatchEvent(new Event("offline"));
    });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    act(() => {
      window.dispatchEvent(new Event("online"));
    });
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(result.current).toBe(true);
  });

  it("015 AC-4: online 事件立即生效，恢复联网不等防抖窗口", () => {
    const { result } = renderHook(() => useOnlineStatus());

    act(() => {
      window.dispatchEvent(new Event("offline"));
    });
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(result.current).toBe(false);

    act(() => {
      window.dispatchEvent(new Event("online"));
    });
    expect(result.current).toBe(true);
  });
});
