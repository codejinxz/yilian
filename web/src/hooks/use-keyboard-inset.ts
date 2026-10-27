import { useEffect } from "react";

/**
 * 把 visualViewport 与布局视口的高度差写进根节点的 --kb 变量。
 * 移动端键盘弹起时，练习页的操作区用该变量把按钮顶到键盘上方；不引入第三方依赖。
 */
export function useKeyboardInset(): void {
  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) {
      return;
    }
    const root = document.documentElement;
    const update = () => {
      const inset = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop);
      root.style.setProperty("--kb", `${Math.round(inset)}px`);
    };
    update();
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    return () => {
      viewport.removeEventListener("resize", update);
      viewport.removeEventListener("scroll", update);
      root.style.removeProperty("--kb");
    };
  }, []);
}
