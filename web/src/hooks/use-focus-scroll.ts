import { useEffect } from "react";

/**
 * 在移动端键盘弹出后把聚焦的输入框滚到可视区域中间，避免提交按钮或输入行被遮挡。
 * @param dependency 依赖值（例如当前题 id）；变化时重新绑定
 */
export function useFocusScroll(dependency?: unknown): void {
  // biome-ignore lint/correctness/useExhaustiveDependencies: dependency 仅作为外部状态变化时重绑监听的触发值，效果体不直接读取它。
  useEffect(() => {
    const handler = (event: FocusEvent) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) {
        return;
      }
      if (!(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) {
        return;
      }
      window.setTimeout(() => {
        target.scrollIntoView({ block: "center", behavior: "smooth" });
      }, 250);
    };
    document.addEventListener("focusin", handler);
    return () => document.removeEventListener("focusin", handler);
  }, [dependency]);
}
