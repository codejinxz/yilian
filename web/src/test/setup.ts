import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// jsdom 未实现 scrollTo，TanStack Router 的滚动恢复需要它存在。
Object.defineProperty(window, "scrollTo", {
  value: vi.fn(),
  writable: true,
});

/** 每个用例后卸载组件、还原 mock，并清除可读的 CSRF cookie，避免用例相互污染。 */
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  // biome-ignore lint/suspicious/noDocumentCookie: 测试需要清理可读的 CSRF cookie
  document.cookie = "XSRF-TOKEN=; Max-Age=0; path=/";
});
