/**
 * 按顺序拼接类名，忽略 false、null、undefined 等假值，便于条件样式与组件默认样式合并。
 * @param values 类名片段；假值会被忽略
 * @returns 以单个空格连接的类名字符串
 */
export function cn(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(" ");
}
