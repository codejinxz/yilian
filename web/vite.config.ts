import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// 三方依赖按包归组；每组包含其传递依赖，避免未匹配的模块落回入口、形成循环 chunk。
const vendorChunks: Record<string, string[]> = {
  "vendor-react": ["react", "react-dom", "scheduler"],
  "vendor-tanstack-router": [
    "@tanstack/react-router",
    "@tanstack/router-core",
    "@tanstack/history",
    "@tanstack/react-store",
    "@tanstack/store",
    "isbot",
    "seroval",
    "seroval-plugins",
    "cookie-es",
    "use-sync-external-store",
  ],
  "vendor-tanstack-query": ["@tanstack/react-query", "@tanstack/query-core"],
  "vendor-zod": ["zod"],
};

/**
 * 从 Rollup 的模块 id 中取出包名；pnpm 的真实路径里可能嵌套多层 node_modules，
 * 因此取最后一个 node_modules 之后的包名（兼容 @scope/name）。
 * @param id Rollup 模块 id
 * @returns 包名；非三方模块返回 undefined
 */
function packageNameOf(id: string): string | undefined {
  const normalized = id.replace(/\\/g, "/");
  const marker = "/node_modules/";
  const index = normalized.lastIndexOf(marker);
  if (index === -1) {
    return undefined;
  }
  const rest = normalized.slice(index + marker.length);
  const match = rest.match(/^((?:@[^/]+\/)?[^/]+)\//);
  return match?.[1];
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/actuator": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 三方依赖单独成 chunk：业务代码变化不会让依赖缓存失效，首屏解析量也更小。
        // 不引入新依赖，也不改业务路由。
        manualChunks(id) {
          const pkg = packageNameOf(id);
          if (!pkg) {
            return undefined;
          }
          for (const [chunk, packages] of Object.entries(vendorChunks)) {
            if (packages.includes(pkg)) {
              return chunk;
            }
          }
          return undefined;
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
