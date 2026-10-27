/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** true 时所有 API 走 web/src/mocks 内置 adapter；默认 false。 */
  readonly VITE_USE_MOCK?: string;
}
