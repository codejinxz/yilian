/**
 * 允许测试或开发时强制打开/关闭 mock；传 null 恢复环境变量判断。
 */
let mockOverride: boolean | null = null;

/**
 * 覆写 mock 开关；测试在渲染前调用。
 * @param value true/false 强制开关，null 恢复 VITE_USE_MOCK
 */
export function setMockEnabled(value: boolean | null): void {
  mockOverride = value;
}

/**
 * 是否使用内置 mock adapter；只有显式 VITE_USE_MOCK=true 才启用，避免真实联调被 mock 抢走。
 * @returns 当前是否启用 mock
 */
export function isMockEnabled(): boolean {
  if (mockOverride !== null) {
    return mockOverride;
  }
  return import.meta.env.VITE_USE_MOCK === "true";
}
