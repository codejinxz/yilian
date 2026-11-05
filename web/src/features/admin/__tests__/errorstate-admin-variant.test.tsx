import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ErrorsPage } from "@/features/admin/ErrorsPage";
import { OverviewPage } from "@/features/admin/OverviewPage";
import { resetApiState } from "@/lib/api";
import { createQueryClient } from "@/lib/query";

/**
 * 管理页错误态语境防回退：管理页各处 ErrorState 必须传 variant="admin"，
 * 错误卡标题为「管理数据加载失败」且不带练习页「答案保存在本机」的离线队列文案——
 * 管理页没有离线作答语义，回退成练习语境会让管理员误以为数据有本地暂存。
 * 组件级语境分化已在 components/__tests__/feedback.test.tsx 锁定，这里锁页面传参。
 */

/** 服务端 ErrorLogItem 的合法形状（admin 模块枚举 + 字符串 id + ISO 时间）。 */
const ERROR_LOG_ITEM = {
  id: "9001",
  traceId: "trace-9001",
  module: "admin",
  exceptionClass: "IllegalStateException",
  message: "示例异常消息",
  requestMethod: "POST",
  requestPath: "/api/admin/example",
  userId: null,
  operator: "admin",
  clientIp: "127.0.0.1",
  createdAt: "2026-01-05T08:00:00Z",
} as const;

/**
 * 构造一个带 headers/json/text 的最小 Response（与 models-page 用例同构）。
 * @param body 响应体
 * @param status HTTP 状态
 * @returns 测试用 Response
 */
function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ "Content-Type": "application/json" }),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

/**
 * 构造后端成功信封。
 * @param data 业务数据
 * @returns code 为 0 的统一响应
 */
function successEnvelope(data: unknown): { code: number; message: string; data: unknown } {
  return { code: 0, message: "success", data };
}

/**
 * 构造异常日志分页成功应答（单条夹具）。
 * @returns error-logs 列表接口的信封
 */
function errorLogPageEnvelope(): { code: number; message: string; data: unknown } {
  return successEnvelope({
    items: [ERROR_LOG_ITEM],
    page: 1,
    size: 10,
    total: 1,
    totalPages: 1,
    hasNext: false,
  });
}

/**
 * 直接渲染管理页（页面只用 react-query，不需要壳层与路由）。
 * @param page 被测管理页组件
 * @returns 无
 */
function renderAdminPage(page: ReactElement) {
  const queryClient = createQueryClient();
  return render(<QueryClientProvider client={queryClient}>{page}</QueryClientProvider>);
}

describe("管理页错误态 admin 语境", () => {
  beforeEach(() => {
    resetApiState();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("概览页断网：错误卡为管理语境——「管理数据加载失败」，重试入口在位，无练习页队列文案", async () => {
    // 后端 8080 未启动 / 断网：fetch 抛 TypeError，请求客户端归一为 status=0 的 ApiError。
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );

    renderAdminPage(<OverviewPage />);

    // 网络错误重试一次（1s 退避）后才落错误态，放宽等待窗口。
    const alert = await screen.findByRole("alert", {}, { timeout: 5000 });
    expect(alert).toHaveTextContent("管理数据加载失败");
    expect(alert).toHaveTextContent("请检查网络连接或确认服务端已启动");
    expect(alert).not.toHaveTextContent("已经填写的答案会保存在本机");
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
  });

  it("异常日志列表 5xx：错误卡同为管理语境，服务端文案透出且无练习页队列文案", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/api/admin/error-logs?")) {
          return jsonResponse({ code: 50000, message: "服务响应异常，请稍后重试" }, 500);
        }
        throw new Error(`unexpected request: ${url}`);
      }),
    );

    renderAdminPage(<ErrorsPage />);

    // 5xx 同样触发一次重试，放宽等待窗口。
    const alert = await screen.findByRole("alert", {}, { timeout: 5000 });
    expect(alert).toHaveTextContent("管理数据加载失败");
    expect(alert).toHaveTextContent("服务响应异常，请稍后重试");
    expect(alert).not.toHaveTextContent("已经填写的答案会保存在本机");
  });

  it("异常详情抽屉加载失败：抽屉内错误卡同为管理语境，不影响列表正常展示", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url.includes("/api/admin/error-logs?") && method === "GET") {
          return jsonResponse(errorLogPageEnvelope());
        }
        if (url.endsWith("/api/admin/error-logs/9001") && method === "GET") {
          return jsonResponse({ code: 50000, message: "服务响应异常，请稍后重试" }, 500);
        }
        throw new Error(`unexpected request: ${method} ${url}`);
      }),
    );

    renderAdminPage(<ErrorsPage />);

    // 列表正常时行内「查看」在位；点开后详情 5xx，抽屉内错误卡也必须是管理语境。
    await screen.findByText(/IllegalStateException/);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "查看" }));
    // 详情 5xx 重试一次（1s 退避），放宽等待窗口。
    const alert = await screen.findByRole("alert", {}, { timeout: 5000 });
    expect(alert).toHaveTextContent("管理数据加载失败");
    expect(alert).toHaveTextContent("服务响应异常，请稍后重试");
    expect(alert).not.toHaveTextContent("已经填写的答案会保存在本机");
  });
});
