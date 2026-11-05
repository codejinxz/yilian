import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ModelsPage } from "@/features/admin/ModelsPage";
import { resetApiState } from "@/lib/api";
import { createQueryClient } from "@/lib/query";

/**
 * ModelsPage「统一模型配置」状态中心 + provider 目录管理（022）：
 * 页面直连真实端点 /api/admin/llm/config 与 /api/admin/llm/providers（mock 层无 /admin/models 演示路径），
 * 因此用 fetch 桩按服务端真实响应形状（API §8.14/§8.15）应答，不经过 src/mocks。
 */

/** 服务端 LlmConfigView 的合法形状（API §8.14.1，022 扩展目录维度）。 */
interface LlmConfigFixture {
  enabled: boolean;
  baseUrl: string;
  model: string;
  hasKey: boolean;
  providerId: string | null;
  protocol: string | null;
  credentialsComplete: boolean;
}

/** 服务端 LlmProviderView 的合法形状（API §8.15.1）。 */
interface LlmProviderFixture {
  id: string;
  name: string;
  protocol: string;
  baseUrl: string;
  model: string;
  hasKey: boolean;
  active: boolean;
}

/** legacy 直配且开启的缺省夹具（022 前 019 用例形态，目录为空）；用例按需覆盖字段。 */
const CONFIGURED_ON: LlmConfigFixture = {
  enabled: true,
  baseUrl: "https://open.bigmodel.cn/api/paas/v4",
  model: "glm-4.7",
  hasKey: true,
  providerId: "legacy",
  protocol: "openai-chat",
  credentialsComplete: true,
};

/** ZCode 网关的目录条目夹具（anthropic-messages 轨、无 env key、已激活）。 */
const ZCODE_GATEWAY: LlmProviderFixture = {
  id: "zcode-gateway",
  name: "ZCode 网关",
  protocol: "anthropic-messages",
  baseUrl: "http://127.0.0.1:47860/gateway/zcode",
  model: "glm-5.3",
  hasKey: false,
  active: true,
};

/** 智谱目录条目夹具（openai-chat 轨、有 env key、未激活）。 */
const ZHIPU: LlmProviderFixture = {
  id: "zhipu",
  name: "智谱直连",
  protocol: "openai-chat",
  baseUrl: "https://open.bigmodel.cn/api/paas/v4",
  model: "glm-4.7",
  hasKey: true,
  active: false,
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
 * /api/admin/llm/config + /api/admin/llm/providers 的 fetch 桩：GET 回当前配置与目录，
 * 写操作按场景应答并记录请求体。
 * @param initial 初始配置（GET 的应答来源，PUT 成功时同步翻转 enabled）
 * @param options 写操作场景与目录初值；putMode=ok 正常应答 / no-credentials 模拟服务端 40000；
 *   providers 为目录初值；providersMode 便于模拟激活中不可删（40000）
 * @returns fetch 桩与观测句柄（GET 次数、写请求记录）
 */
function stubAdminLlmFetch(
  initial: LlmConfigFixture,
  options: {
    putMode?: "ok" | "no-credentials";
    providers?: LlmProviderFixture[];
    providersMode?: "ok" | "delete-active-rejected";
  } = {},
) {
  let current = initial;
  const providers = [...(options.providers ?? [])];
  const observed = {
    getCalls: 0,
    putBodies: [] as Array<Record<string, unknown>>,
    providerPosts: [] as Array<Record<string, unknown>>,
    providerPuts: [] as Array<{ path: string; body: Record<string, unknown> | null }>,
    providerDeletes: [] as string[],
  };
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
      if (options.putMode === "no-credentials") {
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
    if (url.endsWith("/api/admin/llm/providers") && method === "GET") {
      observed.getCalls += 1;
      return jsonResponse(successEnvelope({ items: providers }));
    }
    if (url.endsWith("/api/admin/llm/providers") && method === "POST") {
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
      observed.providerPosts.push(body);
      const created: LlmProviderFixture = {
        id: String(body.id),
        name: String(body.name),
        protocol: String(body.protocol),
        baseUrl: String(body.baseUrl),
        model: String(body.model),
        hasKey: false,
        active: false,
      };
      providers.push(created);
      return jsonResponse(successEnvelope(created));
    }
    const providerMatch = url.match(/\/api\/admin\/llm\/providers\/([^/]+)(\/activate)?$/);
    if (providerMatch) {
      const id = decodeURIComponent(providerMatch[1]);
      if (method === "PUT" && providerMatch[2]) {
        observed.providerPuts.push({ path: `/admin/llm/providers/${id}/activate`, body: null });
        providers.forEach((provider, index) => {
          providers[index] = { ...provider, active: provider.id === id };
        });
        const target = providers.find((provider) => provider.id === id);
        return jsonResponse(successEnvelope(target));
      }
      if (method === "PUT") {
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        observed.providerPuts.push({ path: `/admin/llm/providers/${id}`, body });
        const target = providers.find((provider) => provider.id === id);
        const updated = target
          ? {
              ...target,
              name: String(body.name),
              protocol: String(body.protocol),
              baseUrl: String(body.baseUrl),
              model: String(body.model),
            }
          : target;
        return jsonResponse(successEnvelope(updated));
      }
      if (method === "DELETE") {
        observed.providerDeletes.push(id);
        if (options.providersMode === "delete-active-rejected" && id === "zcode-gateway") {
          return jsonResponse(
            { code: 40000, message: "激活中的 provider 不能删除，请先激活其他 provider" },
            400,
          );
        }
        const index = providers.findIndex((provider) => provider.id === id);
        if (index >= 0) {
          providers.splice(index, 1);
        }
        return jsonResponse(successEnvelope(null));
      }
    }
    throw new Error(`unexpected request: ${method} ${url}`);
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

  it("024 AC-1: 服务不可达（网络断）时错误卡为管理语境——「管理数据加载失败」，不带练习页离线队列文案", async () => {
    // 后端 8080 未启动 / 断网：fetch 抛 TypeError，请求客户端归一为 status=0 的 ApiError。
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );

    renderModelsPage();

    // 网络错误重试一次（1s 退避）后才落错误态，放宽等待窗口。
    const alert = await screen.findByRole("alert", {}, { timeout: 5000 });
    expect(alert).toHaveTextContent("管理数据加载失败");
    expect(alert).toHaveTextContent("请检查网络连接或确认服务端已启动");
    expect(alert).not.toHaveTextContent("已经填写的答案会保存在本机");
    // 重试入口在位：网络恢复后一键重拉。
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
  });

  it("024 AC-1: 服务 5xx 时错误卡同为管理语境，服务端文案透出且无练习页队列文案", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => jsonResponse({ code: 50000, message: "服务响应异常，请稍后重试" }, 500)),
    );

    renderModelsPage();

    // 5xx 同样触发一次重试，放宽等待窗口。
    const alert = await screen.findByRole("alert", {}, { timeout: 5000 });
    expect(alert).toHaveTextContent("管理数据加载失败");
    expect(alert).toHaveTextContent("服务响应异常，请稍后重试");
    expect(alert).not.toHaveTextContent("已经填写的答案会保存在本机");
  });

  it("019 AC-1: hasKey=true 且 enabled=true 时显示已配置、当前模型与答疑「启用中」，不出环境变量指引", async () => {
    stubAdminLlmFetch(CONFIGURED_ON);

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
    stubAdminLlmFetch({
      ...CONFIGURED_ON,
      enabled: false,
      hasKey: false,
      baseUrl: "",
      providerId: null,
      protocol: null,
      credentialsComplete: false,
    });

    renderModelsPage();

    // 「未配置」出现在卡片凭证状态与协议占位两处，任意一处可见即达标。
    expect((await screen.findAllByText("未配置")).length).toBeGreaterThanOrEqual(1);
    // 指引两件套：端点与密钥两个变量名都必须可见，且注明密钥只经环境变量不入库。
    // legacy 形态卡与指引块都含变量名，取「全部匹配」锚定指引本身。
    expect(screen.getAllByText(/YILIAN_LLM_BASE_URL/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/YILIAN_LLM_API_KEY/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/不进配置文件、不回显/)).toBeInTheDocument();
    // 未配凭证即不可用：卡片运行态、答疑关联、AI 功能清单三处都联动为「未开放」。
    expect(screen.getAllByText("未开放")).toHaveLength(3);
    expect(screen.getByRole("button", { name: "启用答疑" })).toBeEnabled();
  });

  it("017R2 OBS-1: 开关已开且凭证按协议口径不齐（credentialsComplete=false）时，答疑关联显示「未生效（缺凭证）」而非笼统的未开放，并附下一步说明", async () => {
    // legacy openai 轨组合：enabled=true + 凭证不齐（hasKey=false 是不齐的原因），
    // 管理员视角「开关是开的、功能却不存在」。齐备性判定以服务端 credentialsComplete
    // 为准（协议分口径），hasKey 只是展示徽标。
    stubAdminLlmFetch({
      ...CONFIGURED_ON,
      hasKey: false,
      baseUrl: "",
      credentialsComplete: false,
    });

    renderModelsPage();

    // 答疑关联与 AI 功能清单两处联动位都归一为「未生效（缺凭证）」。
    expect(await screen.findAllByText("未生效（缺凭证）")).toHaveLength(2);
    // 展示层归一不影响指引：缺什么（环境变量两件套）与下一步（重启生效）同时可见。
    expect(screen.getByText(/YILIAN_LLM_API_KEY/)).toBeInTheDocument();
    expect(screen.getByText(/开关已开启但未生效/)).toBeInTheDocument();
    // 运行态卡仍如实显示未开放（不谎称运行中）。
    expect(screen.getByText("未配置")).toBeInTheDocument();
  });

  it("023 AC-1: anthropic 轨 hasKey=false 是合法可启用态——credentialsComplete=true 且开关已开时答疑关联为「启用中」，不出现缺凭证提示", async () => {
    // anthropic-messages 轨 key 可空（齐备只看端点）：hasKey=false 不是缺凭证。
    // 若展示仍按 openai 语义把 hasKey=false 当缺凭证，管理员在网关部署上会看到
    // 「未生效（缺凭证）」与事实相反——这里按 022 后的 credentialsComplete 口径锁死。
    stubAdminLlmFetch(
      { ...CONFIGURED_ON, hasKey: false, credentialsComplete: true },
      { providers: [ZCODE_GATEWAY] },
    );

    renderModelsPage();

    // 答疑关联与 AI 功能清单两处联动位都是「启用中」。
    expect(await screen.findAllByText("启用中")).toHaveLength(2);
    // 不出现缺凭证形态：无「未生效（缺凭证）」、无环境变量指引块、无对称说明行。
    expect(screen.queryByText("未生效（缺凭证）")).not.toBeInTheDocument();
    expect(screen.queryByText(/开关已开启但未生效/)).not.toBeInTheDocument();
    expect(screen.queryByText(/凭证不齐/)).not.toBeInTheDocument();
    // 运行态为「运行中」，凭证状态按协议口径显示已配置。
    expect(screen.getByText("运行中")).toBeInTheDocument();
    expect(screen.getByText("已配置")).toBeInTheDocument();
    // provider 卡上 anthropic 轨无 key 徽标是「无需 Key」而非「缺 Key」。
    expect(screen.getByText("无需 Key")).toBeInTheDocument();
    expect(screen.queryByText("缺 Key")).not.toBeInTheDocument();
  });

  it("019 AC-1: 点击启用提交 PUT {enabled:true}，成功后失效缓存重新拉取并提示", async () => {
    const observed = stubAdminLlmFetch({ ...CONFIGURED_ON, enabled: false });

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "启用答疑" }));

    // 请求形状：只受理 enabled（baseUrl/model/apiKey 不传，服务端对非空值 40000）。
    await waitFor(() => expect(observed.putBodies).toHaveLength(1));
    expect(observed.putBodies[0]).toEqual({ enabled: true });
    // 成功即失效缓存：invalidateQueries 触发第二次 GET（config 与 providers 各一）。
    await waitFor(() => expect(observed.getCalls).toBeGreaterThanOrEqual(2));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "停用答疑" })).toBeInTheDocument(),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(/答疑已启用/);
  });

  it("019 AC-1: 未配凭证时点启用，服务端 40000 文案透出为提示，开关不翻转、缓存不失效", async () => {
    const observed = stubAdminLlmFetch(
      {
        ...CONFIGURED_ON,
        enabled: false,
        hasKey: false,
        baseUrl: "",
        providerId: null,
        protocol: null,
        credentialsComplete: false,
      },
      { putMode: "no-credentials" },
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
    expect(observed.getCalls).toBe(2);
  });
});

describe("ModelsPage provider 目录管理（022 AC-3）", () => {
  beforeEach(() => {
    resetApiState();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("022 AC-3: 目录列表卡展示名称/协议徽标/端点/模型/hasKey 徽标，激活条目高亮「激活中」", async () => {
    stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY, ZHIPU] },
    );

    renderModelsPage();

    expect(await screen.findByText("ZCode 网关")).toBeInTheDocument();
    // 协议徽标与 hasKey 徽标：anthropic 轨无 key 显示「无需 Key」，openai 轨有 key 显示「有 Key」。
    // 协议徽标在卡片与顶部生效态卡各出现一次，任意可见即达标。
    expect((await screen.findAllByText("anthropic-messages")).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("无需 Key")).toBeInTheDocument();
    expect(screen.getByText("有 Key")).toBeInTheDocument();
    // 卡内端点与模型共享一行文本节点，按卡片容器断言内容。
    const zcodeCard = screen.getByLabelText("provider zcode-gateway");
    expect(zcodeCard.textContent).toContain("http://127.0.0.1:47860/gateway/zcode");
    expect(zcodeCard.textContent).toContain("glm-5.3");
    // 激活高亮与操作按钮：激活条目无「激活」按钮，未激活条目有。
    expect(screen.getByText("激活中")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "激活 provider zhipu" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "激活 provider zcode-gateway" })).toBeNull();
    // AI 功能清单按激活 provider 联动出数据源。
    expect(screen.getByText(/数据源：ZCode 网关（anthropic-messages）/)).toBeInTheDocument();
  });

  it("022 AC-3: 目录为空时显示 legacy 环境变量直配形态与切换指引", async () => {
    stubAdminLlmFetch(CONFIGURED_ON);

    renderModelsPage();

    expect(await screen.findByText("环境变量直配（legacy 单 provider）")).toBeInTheDocument();
    expect(screen.getByText(/新增 provider 并激活后由目录接管/)).toBeInTheDocument();
    // 回退语义明示：本卡只是目录为空时的 legacy 形态，只读展示、不承担目录管理。
    expect(screen.getByText(/目录为空时的回退形态/)).toBeInTheDocument();
    // legacy 形态下无任何 provider 操作按钮。
    expect(screen.queryByRole("button", { name: "新增 provider" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: /^激活 provider/ })).toBeNull();
  });

  it("022 AC-3: 新增 provider 提交 POST 元数据形状（无 apiKey 字段），成功后目录重新拉取", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY] },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "新增 provider" }));
    await user.type(screen.getByLabelText("ID"), "zai");
    await user.type(screen.getByLabelText("名称"), "ZCode 网关（远程）");
    await user.selectOptions(screen.getByLabelText("协议"), "anthropic-messages");
    await user.type(
      screen.getByLabelText("端点 Base URL"),
      "http://10.150.191.220:47860/gateway/zcode",
    );
    await user.type(screen.getByLabelText("模型名"), "glm-5.3-flash");
    await user.click(screen.getByRole("button", { name: "保存" }));

    // 请求形状：只含元数据，密钥不入请求体。
    await waitFor(() => expect(observed.providerPosts).toHaveLength(1));
    expect(observed.providerPosts[0]).toEqual({
      id: "zai",
      name: "ZCode 网关（远程）",
      protocol: "anthropic-messages",
      baseUrl: "http://10.150.191.220:47860/gateway/zcode",
      model: "glm-5.3-flash",
    });
    expect(JSON.stringify(observed.providerPosts[0])).not.toContain("apiKey");
    // 成功提示 + 目录刷新（新条目出现在列表）。
    expect(await screen.findByRole("status")).toHaveTextContent(/已新增/);
    await waitFor(() => expect(screen.getByText("ZCode 网关（远程）")).toBeInTheDocument());
  });

  it("022 AC-3: 激活提交 PUT /{id}/activate，成功后激活态翻转并提示", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY, ZHIPU] },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "激活 provider zhipu" }));

    await waitFor(() => expect(observed.providerPuts).toHaveLength(1));
    expect(observed.providerPuts[0].path).toBe("/admin/llm/providers/zhipu/activate");
    expect(await screen.findByRole("status")).toHaveTextContent(/已激活/);
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "激活 provider zcode-gateway" }),
      ).toBeInTheDocument(),
    );
  });

  it("022 AC-3: 删除需确认；激活中的 provider 服务端 40000 文案透出且不删除", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY, ZHIPU], providersMode: "delete-active-rejected" },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "删除 provider zcode-gateway" }));
    // 确认框讲清后果（只删元数据、密钥不动），确认后才发 DELETE。
    expect(await screen.findByRole("dialog")).toHaveTextContent(/只删目录元数据/);
    await user.click(screen.getByRole("button", { name: "删除" }));
    await waitFor(() => expect(observed.providerDeletes).toEqual(["zcode-gateway"]));
    expect(await screen.findByRole("status")).toHaveTextContent(/激活中的 provider 不能删除/);
    // 条目仍在目录里。
    expect(screen.getByText("ZCode 网关")).toBeInTheDocument();
  });

  it("022 AC-3: 编辑回填条目且 id 固定不可改，提交 PUT /{id} 整体覆盖元数据", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY] },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑 provider zcode-gateway" }));
    const idInput = screen.getByLabelText("ID") as HTMLInputElement;
    expect(idInput).toBeDisabled();
    expect(idInput.value).toBe("zcode-gateway");
    const nameInput = screen.getByLabelText("名称") as HTMLInputElement;
    await user.clear(nameInput);
    await user.type(nameInput, "ZCode 本地网关");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(observed.providerPuts).toHaveLength(1));
    expect(observed.providerPuts[0].path).toBe("/admin/llm/providers/zcode-gateway");
    expect(observed.providerPuts[0].body).toEqual({
      name: "ZCode 本地网关",
      protocol: "anthropic-messages",
      baseUrl: "http://127.0.0.1:47860/gateway/zcode",
      model: "glm-5.3",
    });
  });
});

describe("ModelsPage provider 表单去预设与全字段动态编辑", () => {
  beforeEach(() => {
    resetApiState();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("新增抽屉为空白表单：无预置模板入口，ID/名称/端点/模型初始为空、协议未预选", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY] },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "新增 provider" }));

    // 预置模板入口已移除：抽屉内不存在任何「一键预填」按钮。
    expect(screen.queryByText(/填入.*模板/)).not.toBeInTheDocument();
    // 空白表单：文本字段全空，不携带任何默认值。
    expect((screen.getByLabelText("ID") as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText("名称") as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText("端点 Base URL") as HTMLInputElement).value).toBe("");
    expect((screen.getByLabelText("模型名") as HTMLInputElement).value).toBe("");
    const protocolSelect = screen.getByLabelText("协议") as HTMLSelectElement;
    expect(protocolSelect.value).toBe("");
    // 占位空选项 + 恰好三个协议可选项，顺序与协议目录一致。
    expect(screen.getByRole("option", { name: "请选择协议" })).toBeInTheDocument();
    const protocols = [...protocolSelect.options]
      .map((option) => option.value)
      .filter((value) => value !== "");
    expect(protocols).toEqual(["anthropic-messages", "openai-chat", "openai-responses"]);
    // 打开抽屉不产生任何写请求。
    expect(observed.providerPosts).toHaveLength(0);
  });

  it("未选协议时保存被拦截；三协议任选其一后全字段手填保存成功", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY] },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "新增 provider" }));
    await user.type(screen.getByLabelText("ID"), "glm-openai");
    await user.type(screen.getByLabelText("名称"), "自建网关");
    await user.type(screen.getByLabelText("端点 Base URL"), "https://llm.example.com/v4");
    await user.type(screen.getByLabelText("模型名"), "glm-5.3-flash");
    // 协议留空提交：被前端校验拦截，不发出 POST。
    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("status")).toHaveTextContent("请选择协议");
    expect(observed.providerPosts).toHaveLength(0);

    // 任选一个协议后保存：POST 只含手填元数据，无 apiKey 字段。
    await user.selectOptions(screen.getByLabelText("协议"), "openai-responses");
    await user.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(observed.providerPosts).toHaveLength(1));
    expect(observed.providerPosts[0]).toEqual({
      id: "glm-openai",
      name: "自建网关",
      protocol: "openai-responses",
      baseUrl: "https://llm.example.com/v4",
      model: "glm-5.3-flash",
    });
    expect(JSON.stringify(observed.providerPosts[0])).not.toContain("apiKey");
    expect(await screen.findByRole("status")).toHaveTextContent(/已新增/);
  });

  it("编辑抽屉全字段可改：名称/协议/端点/模型逐项修改后整体提交，id 走路径不变", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY] },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑 provider zcode-gateway" }));
    // 协议回填条目当前值，可改为三协议中的任意另一个。
    const protocolSelect = screen.getByLabelText("协议") as HTMLSelectElement;
    expect(protocolSelect.value).toBe("anthropic-messages");
    await user.clear(screen.getByLabelText("名称"));
    await user.type(screen.getByLabelText("名称"), "内网网关");
    await user.selectOptions(protocolSelect, "openai-chat");
    await user.clear(screen.getByLabelText("端点 Base URL"));
    await user.type(screen.getByLabelText("端点 Base URL"), "http://10.0.0.8:8080/v4");
    await user.clear(screen.getByLabelText("模型名"));
    await user.type(screen.getByLabelText("模型名"), "glm-4.7-air");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(observed.providerPuts).toHaveLength(1));
    expect(observed.providerPuts[0].path).toBe("/admin/llm/providers/zcode-gateway");
    expect(observed.providerPuts[0].body).toEqual({
      name: "内网网关",
      protocol: "openai-chat",
      baseUrl: "http://10.0.0.8:8080/v4",
      model: "glm-4.7-air",
    });
  });

  it("顶部说明为现状中性描述：不含 YILIAN_LLM_KEY 硬编码指引，也不暴露存储实现细节", async () => {
    // 凭证缺失场景：条目变量名只允许出现在按 providerId 动态计算的指引块里，
    // 顶部说明不得再硬编码「YILIAN_LLM_KEY_<大写ID>」式指引或唯一通道承诺。
    stubAdminLlmFetch(
      {
        ...CONFIGURED_ON,
        providerId: "zhipu",
        protocol: "openai-chat",
        hasKey: false,
        credentialsComplete: false,
      },
      { providers: [ZHIPU] },
    );

    renderModelsPage();

    // 加载分支也渲染顶部说明（同一段文案），先等目录数据就绪再断言，避免撞上加载态。
    await screen.findByText("智谱直连");
    const topNote = await screen.findByText(/本页管理 provider 目录/);
    expect(topNote.textContent).not.toContain("YILIAN_LLM_KEY");
    // env 字样允许保留，但必须是描述现状（按条目 ID 索引环境变量），且不对通道做唯一性承诺。
    expect(topNote.textContent).toContain("环境变量");
    // 全页不再出现存储实现细节（app_settings）与模板预填入口。
    expect(screen.queryByText(/app_settings/)).not.toBeInTheDocument();
    expect(screen.queryByText(/填入.*模板/)).not.toBeInTheDocument();
    // 条目密钥变量名按现状动态给出：指引块随激活条目计算，顶部说明不再越俎代庖。
    expect(screen.getByText(/YILIAN_LLM_KEY_ZHIPU/)).toBeInTheDocument();
  });
});
