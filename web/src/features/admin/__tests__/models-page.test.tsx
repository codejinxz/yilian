import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ModelsPage } from "@/features/admin/ModelsPage";
import { resetApiState } from "@/lib/api";
import { createQueryClient } from "@/lib/query";

/**
 * ModelsPage「统一模型配置」状态中心（019）：
 * 页面直连真实端点 /api/admin/llm/config（mock 层无 /admin/models 演示路径，见页面头注释），
 * 因此用 fetch 桩按服务端真实响应形状（API §8.14）应答，不经过 src/mocks。
 */

/** 服务端 LlmConfigView 的合法形状（API §8.14.1）。 */
interface LlmConfigFixture {
  enabled: boolean;
  baseUrl: string;
  model: string;
  hasKey: boolean;
}

/** 已配置且开启的缺省夹具；用例按需覆盖字段。 */
const CONFIGURED_ON: LlmConfigFixture = {
  enabled: true,
  baseUrl: "https://open.bigmodel.cn/api/paas/v4",
  model: "glm-4.7",
  hasKey: true,
};

/**
 * 构造一个带 headers/json/text 的最小 Response（与 admin-shell 用例同构）。
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
 * /api/admin/llm/config 的 fetch 桩：GET 回当前配置，PUT 按场景应答并记录请求体。
 * @param initial 初始配置（GET 的应答来源，PUT 成功时同步翻转 enabled）
 * @param putMode ok=正常应答；no-credentials=模拟服务端 40000（enabled=true 且无凭证）
 * @returns fetch 桩与观测句柄（GET 次数、PUT 请求体）
 */
function stubLlmConfigFetch(initial: LlmConfigFixture, putMode: "ok" | "no-credentials" = "ok") {
  let current = initial;
  const observed = { getCalls: 0, putBodies: [] as Array<Record<string, unknown>> };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    if (url.endsWith("/api/admin/llm/config") && method === "GET") {
      observed.getCalls += 1;
      return jsonResponse(successEnvelope(current));
    }
    if (url.endsWith("/api/admin/llm/config") && method === "PUT") {
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
      observed.putBodies.push(body);
      if (putMode === "no-credentials") {
        return jsonResponse(
          {
            code: 40000,
            message: "尚未配置 YILIAN_LLM_BASE_URL / YILIAN_LLM_API_KEY，无法开启答疑",
          },
          400,
        );
      }
      current = { ...current, enabled: Boolean(body.enabled) };
      return jsonResponse(successEnvelope(current));
    }
    throw new Error(`unexpected request: ${url}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  return observed;
}

/**
 * 直接渲染 ModelsPage（页面只用 react-query，不需要壳层与路由）。
 * @returns 无
 */
function renderModelsPage() {
  const queryClient = createQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <ModelsPage />
    </QueryClientProvider>,
  );
}

describe("ModelsPage 统一模型配置状态中心", () => {
  beforeEach(() => {
    resetApiState();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("019 AC-1: hasKey=true 且 enabled=true 时显示已配置、当前模型与答疑「启用中」，不出环境变量指引", async () => {
    stubLlmConfigFetch(CONFIGURED_ON);

    renderModelsPage();

    // 凭证与开关联动：卡内答疑关联与 AI 功能清单两处都应为「启用中」。
    expect(await screen.findAllByText("启用中")).toHaveLength(2);
    expect(screen.getByText("已配置")).toBeInTheDocument();
    expect(screen.getByText("glm-4.7")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "停用答疑" })).toBeEnabled();
    // 已配置时不出现配置指引，也不出现占位误导。
    expect(screen.queryByText(/YILIAN_LLM_API_KEY/)).not.toBeInTheDocument();
    expect(screen.queryByText(/尚未配置模型凭证/)).not.toBeInTheDocument();
  });

  it("019 AC-1: hasKey=false 时显示未配置与 YILIAN_LLM_* 环境变量两件套指引（注明密钥不入库），答疑联动为「未开放」", async () => {
    stubLlmConfigFetch({ ...CONFIGURED_ON, enabled: false, hasKey: false, baseUrl: "" });

    renderModelsPage();

    expect(await screen.findByText("未配置")).toBeInTheDocument();
    // 指引两件套：端点与密钥两个变量名都必须可见，且注明密钥只经环境变量不入库。
    // 页头导语含同义句，「不进配置文件」是指引块独有的表述，用它锚定指引本身。
    expect(screen.getByText(/YILIAN_LLM_BASE_URL/)).toBeInTheDocument();
    expect(screen.getByText(/YILIAN_LLM_API_KEY/)).toBeInTheDocument();
    expect(screen.getByText(/不进配置文件、不回显/)).toBeInTheDocument();
    // 未配凭证即不可用：卡片运行态、答疑关联、AI 功能清单三处都联动为「未开放」。
    expect(screen.getAllByText("未开放")).toHaveLength(3);
    expect(screen.getByRole("button", { name: "启用答疑" })).toBeEnabled();
  });

  it("017R2 OBS-1: hasKey=false 但开关已开时，答疑关联显示「未生效（缺凭证）」而非笼统的未开放，并附下一步说明", async () => {
    // 021 冒烟遗留的真实组合：enabled=true + 无凭证，管理员视角「开关是开的、功能却不存在」。
    stubLlmConfigFetch({ ...CONFIGURED_ON, hasKey: false, baseUrl: "" });

    renderModelsPage();

    // 答疑关联与 AI 功能清单两处联动位都归一为「未生效（缺凭证）」。
    expect(await screen.findAllByText("未生效（缺凭证）")).toHaveLength(2);
    // 展示层归一不影响指引：缺什么（环境变量两件套）与下一步（重启生效）同时可见。
    expect(screen.getByText(/YILIAN_LLM_API_KEY/)).toBeInTheDocument();
    expect(screen.getByText(/开关已开启但未生效/)).toBeInTheDocument();
    // 运行态卡仍如实显示未开放（不谎称运行中）。
    expect(screen.getByText("未配置")).toBeInTheDocument();
  });

  it("019 AC-1: 点击启用提交 PUT {enabled:true}，成功后失效缓存重新拉取并提示", async () => {
    const observed = stubLlmConfigFetch({ ...CONFIGURED_ON, enabled: false });

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "启用答疑" }));

    // 请求形状：只受理 enabled（baseUrl/model/apiKey 不传，服务端对非空值 40000）。
    await waitFor(() => expect(observed.putBodies).toHaveLength(1));
    expect(observed.putBodies[0]).toEqual({ enabled: true });
    // 成功即失效缓存：invalidateQueries 触发第二次 GET，开关状态以服务端回读为准。
    await waitFor(() => expect(observed.getCalls).toBe(2));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "停用答疑" })).toBeInTheDocument(),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(/答疑已启用/);
  });

  it("019 AC-1: 未配凭证时点启用，服务端 40000 文案透出为提示，开关不翻转、缓存不失效", async () => {
    const observed = stubLlmConfigFetch(
      { ...CONFIGURED_ON, enabled: false, hasKey: false, baseUrl: "" },
      "no-credentials",
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "启用答疑" }));

    // 失败路径：业务文案原样透出（指引可见的文案与服务端提示一致），状态不翻转。
    expect(await screen.findByRole("status")).toHaveTextContent(
      "尚未配置 YILIAN_LLM_BASE_URL / YILIAN_LLM_API_KEY，无法开启答疑",
    );
    await waitFor(() => expect(observed.putBodies).toHaveLength(1));
    // 失败不失效缓存：GET 只发生在首次挂载。
    await waitFor(() => expect(screen.getByRole("button", { name: "启用答疑" })).toBeEnabled());
    expect(observed.getCalls).toBe(1);
  });
});
