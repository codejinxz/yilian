import { useEffect, useState } from "react";

/** offline 事件的防抖窗口：短于它的断开抖动不翻转在线态。 */
const OFFLINE_DEBOUNCE_MS = 1500;

/**
 * 订阅浏览器在线状态；首次渲染用 navigator.onLine 兜底。
 *
 * <p>防御动机：浏览器可能发出瞬时的 offline→online 抖动（网络适配器切换、VPN
 * 重连、系统网络探测），提交入口 `if (!online) enqueue` 若信任瞬时态，会把可正常
 * 提交的答卷当场入队，随后的自动补交变成同载荷重放。offline 事件需持续超过
 * 防抖窗口才翻转状态；窗口内的 online 事件直接取消。真正断网时提交并不依赖本状态：
 * fetch 立即失败，错误路径会凭实时的 navigator.onLine 判定并入队，答案不会丢。
 *
 * @returns 当前是否在线
 */
export function useOnlineStatus(): boolean {
  const [online, setOnline] = useState(() =>
    typeof navigator === "undefined" ? true : navigator.onLine,
  );

  useEffect(() => {
    let offlineTimer: number | null = null;
    const clearOfflineTimer = () => {
      if (offlineTimer !== null) {
        window.clearTimeout(offlineTimer);
        offlineTimer = null;
      }
    };
    const handleOnline = () => {
      clearOfflineTimer();
      setOnline(true);
    };
    const handleOffline = () => {
      clearOfflineTimer();
      offlineTimer = window.setTimeout(() => {
        offlineTimer = null;
        setOnline(false);
      }, OFFLINE_DEBOUNCE_MS);
    };
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    return () => {
      clearOfflineTimer();
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  return online;
}
