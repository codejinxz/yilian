import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ModelsPage } from "@/features/admin/ModelsPage";
import { resetApiState } from "@/lib/api";
import { createQueryClient, queryKeys } from "@/lib/query";

/**
 * ModelsPage「统一模型配置」状态中心 + provider 目录管理（022）：
 * 页面直连真实端点 /api/admin/llm/config 与 /api/admin/llm/providers（mock 层无 /admin/models 演示路径），
 * 因此用 fetch 桩按服务端真实响应形状（API §8.14/§8.15）应答，不经过 src/mocks。
 */

/** 服务端 LlmConfigView 的合法形状（API §8.14.1，028 扩 source/keyLast4）。 */
interface LlmConfigFixture {
  enabled: boolean;
  baseUrl: string;
  model: string;
  hasKey: boolean;
  providerId: string | null;
  protocol: string | null;
  credentialsComplete: boolean;
  /** legacy 形态取值来源：custom=存在 tutor 覆盖；env=纯环境变量（目录激活态恒为 env）。 */
  source: "custom" | "env";
  /** tutor 密钥行尾 4 位；env 态密钥/目录激活态为 null。 */
  keyLast4: string | null;
}

/** 服务端 LlmProviderView 的合法形状（API §8.15.1；027B 增 keyLast4）。 */
interface LlmProviderFixture {
  id: string;
  name: string;
  protocol: string;
  baseUrl: string;
  model: string;
  hasKey: boolean;
  keyLast4: string | null;
  active: boolean;
}

/** legacy 直配且开启的缺省夹具（019 用例形态，目录为空、纯 env）；用例按需覆盖字段。 */
const CONFIGURED_ON: LlmConfigFixture = {
  enabled: true,
  baseUrl: "https://open.bigmodel.cn/api/paas/v4",
  model: "glm-4.7",
  hasKey: true,
  providerId: "legacy",
  protocol: "openai-chat",
  credentialsComplete: true,
  source: "env",
  keyLast4: null,
};

/** ZCode 网关的目录条目夹具（anthropic-messages 轨、无 env key、已激活）。 */
const ZCODE_GATEWAY: LlmProviderFixture = {
  id: "zcode-gateway",
  name: "ZCode 网关",
  protocol: "anthropic-messages",
  baseUrl: "http://127.0.0.1:47860/gateway/zcode",
  model: "glm-5.3",
  hasKey: false,
  keyLast4: null,
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
  keyLast4: null,
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
      // 028 状态型字段语义：baseUrl/model 出现即表达完整意图（值=落覆盖，空串/缺省=清除回 env）；
      // apiKey 写-only（非空白=加密入库投影新尾号），clearKey=true 删 tutor 密钥行。
      const baseUrl = typeof body.baseUrl === "string" ? body.baseUrl : "";
      const model = typeof body.model === "string" ? body.model : "";
      const apiKeyGiven = typeof body.apiKey === "string" && body.apiKey !== "";
      const clearKey = body.clearKey === true;
      current = {
        ...current,
        enabled: Boolean(body.enabled),
        baseUrl,
        model,
        source: baseUrl !== "" || model !== "" ? "custom" : "env",
        hasKey: apiKeyGiven ? true : clearKey ? false : current.hasKey,
        keyLast4: apiKeyGiven ? String(body.apiKey).slice(-4) : clearKey ? null : current.keyLast4,
      };
      return jsonResponse(successEnvelope(current));
    }
    if (url.endsWith("/api/admin/llm/providers") && method === "GET") {
      observed.getCalls += 1;
      return jsonResponse(successEnvelope({ items: providers }));
    }
    if (url.endsWith("/api/admin/llm/providers") && method === "POST") {
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
      observed.providerPosts.push(body);
      // 027B：带 apiKey（写-only）时服务端加密入库，投影只回 hasKey + 尾 4 位。
      const hasKey = typeof body.apiKey === "string" && body.apiKey !== "";
      const created: LlmProviderFixture = {
        id: String(body.id),
        name: String(body.name),
        protocol: String(body.protocol),
        baseUrl: String(body.baseUrl),
        model: String(body.model),
        hasKey,
        keyLast4: hasKey ? String(body.apiKey).slice(-4) : null,
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
        // 027B：apiKey 非空白=加密覆盖（投影换新尾号）；空/缺省=不变更（保留旧尾号）。
        const apiKeyGiven = typeof body.apiKey === "string" && body.apiKey !== "";
        const updated = target
          ? {
              ...target,
              name: String(body.name),
              protocol: String(body.protocol),
              baseUrl: String(body.baseUrl),
              model: String(body.model),
              hasKey: apiKeyGiven || target.hasKey,
              keyLast4: apiKeyGiven ? String(body.apiKey).slice(-4) : target.keyLast4,
            }
          : target;
        if (updated) {
          const index = providers.findIndex((provider) => provider.id === id);
          providers[index] = updated;
        }
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

  it("019 AC-1: hasKey=true 且 enabled=true 时显示已配置、当前模型（inline 回显）与答疑「启用中」，不出环境变量指引", async () => {
    stubAdminLlmFetch(CONFIGURED_ON);

    renderModelsPage();

    // 凭证与开关联动：卡内答疑关联与 AI 功能清单两处都应为「启用中」。
    expect(await screen.findAllByText("启用中")).toHaveLength(2);
    expect(screen.getByText("已配置")).toBeInTheDocument();
    // 028：legacy 态当前模型为 inline 输入（默认回显生效值），不再是纯文本。
    expect(screen.getByLabelText("当前模型")).toHaveValue("glm-4.7");
    expect(screen.getByLabelText("生效端点")).toHaveValue("https://open.bigmodel.cn/api/paas/v4");
    expect(screen.getByRole("button", { name: "停用答疑" })).toBeEnabled();
    // 已配置时不出现配置指引，也不出现占位误导。
    expect(screen.queryByText(/YILIAN_LLM_API_KEY/)).not.toBeInTheDocument();
    expect(screen.queryByText(/尚未配置模型凭证/)).not.toBeInTheDocument();
  });

  it("019 AC-1: hasKey=false 时显示未配置与 YILIAN_LLM_* 环境变量两件套指引（注明可加密入库或环境变量注入），答疑联动为「未开放」", async () => {
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
    // 指引两件套：端点与密钥两个变量名都必须可见，且注明密钥加密入库/环境变量两条路都通。
    // legacy 形态卡与指引块都含变量名，取「全部匹配」锚定指引本身。
    expect(screen.getAllByText(/YILIAN_LLM_BASE_URL/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/YILIAN_LLM_API_KEY/).length).toBeGreaterThanOrEqual(1);
    expect(
      screen.getByText(/可加密入库或经环境变量注入，明文不回显、不进日志/),
    ).toBeInTheDocument();
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
    // 展示层归一不影响指引：缺什么（环境变量两件套）与下一步（inline 补齐）同时可见。
    // 028：密钥输入提示与指引块都含变量名，取「全部匹配」计数。
    expect(screen.getAllByText(/YILIAN_LLM_API_KEY/).length).toBeGreaterThanOrEqual(1);
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

  it("019 AC-1: 点击启用提交 PUT（enabled 翻转 + 表单当前值），成功后乐观刷新缓存并提示", async () => {
    const observed = stubAdminLlmFetch({ ...CONFIGURED_ON, enabled: false });

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "启用答疑" }));

    // 028 状态型字段语义：legacy 态开关与表单当前值一起提交（裸传 enabled 会清掉既有覆盖）。
    await waitFor(() => expect(observed.putBodies).toHaveLength(1));
    expect(observed.putBodies[0]).toEqual({
      enabled: true,
      baseUrl: "https://open.bigmodel.cn/api/paas/v4",
      model: "glm-4.7",
    });
    // 成功即乐观刷新 + 失效缓存：回执落缓存后 invalidate 再拉（config 与 providers 各一）。
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
    // 确认框讲清后果（入库密钥一并删除、env 密钥不动），确认后才发 DELETE。
    expect(await screen.findByRole("dialog")).toHaveTextContent(/其入库密钥一并删除/);
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

describe("ModelsPage provider 密钥管理（027B 写-only 与投影）", () => {
  beforeEach(() => {
    resetApiState();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  /** 入库密钥条目夹具（openai 轨、DB 态：hasKey + 尾 4 位）。 */
  const STORED_KEY: LlmProviderFixture = {
    id: "stored-prov",
    name: "入库密钥条目",
    protocol: "openai-chat",
    baseUrl: "https://open.bigmodel.cn/api/paas/v4",
    model: "glm-4.7",
    hasKey: true,
    keyLast4: "8811",
    active: true,
  };

  it("027B AC-4: 列表卡展示 last4（入库态）且 env 态不显示 last4，hasKey 徽标口径不受影响", async () => {
    stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "stored-prov", protocol: "openai-chat" },
      { providers: [STORED_KEY, ZHIPU] },
    );

    renderModelsPage();

    await screen.findByText("入库密钥条目");
    // 入库态：有 Key 徽标 + 尾号 ••••8811。
    const storedCard = screen.getByLabelText("provider stored-prov");
    expect(storedCard.textContent).toContain("尾号 ••••8811");
    // env 态：有 Key 徽标但无尾号（keyLast4=null 不显示）。
    const envCard = screen.getByLabelText("provider zhipu");
    expect(envCard.textContent).toContain("有 Key");
    expect(envCard.textContent).not.toContain("尾号");
  });

  it("027B AC-4: 编辑抽屉密钥字段写-only——留空提交不携带 apiKey，填入提交携带明文且输入框为 password 掩码", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "stored-prov", protocol: "openai-chat" },
      { providers: [STORED_KEY] },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑 provider stored-prov" }));

    // 写-only：输入框为 password 掩码、初始空白（已存密钥不回填），提示尾号。
    const keyInput = screen.getByLabelText("API 密钥") as HTMLInputElement;
    expect(keyInput).toHaveAttribute("type", "password");
    expect(keyInput.value).toBe("");
    expect(screen.getByText(/已存密钥尾号 ••••8811/)).toBeInTheDocument();

    // 留空提交：PUT body 不携带 apiKey（=不变更）。
    await user.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(observed.providerPuts).toHaveLength(1));
    expect(observed.providerPuts[0].body).not.toHaveProperty("apiKey");

    // 再次打开：填入新密钥提交，body 携带明文（写-only 请求侧允许），响应只投影尾号。
    await user.click(await screen.findByRole("button", { name: "编辑 provider stored-prov" }));
    const keyInput2 = screen.getByLabelText("API 密钥") as HTMLInputElement;
    await user.type(keyInput2, "sk-rotated-plaintext-99ab");
    await user.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(observed.providerPuts).toHaveLength(2));
    expect((observed.providerPuts[1].body as Record<string, unknown>).apiKey).toBe(
      "sk-rotated-plaintext-99ab",
    );

    // 保存成功后抽屉关闭：明文不留在 DOM（无明文回显红线）。
    await waitFor(() => expect(screen.queryByLabelText("API 密钥")).not.toBeInTheDocument());
    expect(screen.getByLabelText("provider stored-prov").textContent).toContain("尾号 ••••99ab");
    expect(screen.queryByText("sk-rotated-plaintext-99ab")).not.toBeInTheDocument();
  });

  it("027B AC-4: 新增 provider 携带 apiKey 时 POST 写-only 字段，成功后卡片按 hasKey+尾号投影", async () => {
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "legacy", protocol: "openai-chat" },
      { providers: [] },
    );

    renderModelsPage();

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "新增 provider" }));
    await user.type(screen.getByLabelText("ID"), "new-prov");
    await user.type(screen.getByLabelText("名称"), "新条目");
    await user.selectOptions(screen.getByLabelText("协议"), "openai-chat");
    await user.type(screen.getByLabelText("端点 Base URL"), "https://x");
    await user.type(screen.getByLabelText("模型名"), "glm-4.7");
    // 新增态密钥为可选：占位提示讲清「填入=加密入库；留空走环境变量」。
    expect(screen.getByText(/可选：填入=加密入库/)).toBeInTheDocument();
    await user.type(screen.getByLabelText("API 密钥"), "sk-new-plaintext-cd34");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(observed.providerPosts).toHaveLength(1));
    expect((observed.providerPosts[0] as Record<string, unknown>).apiKey).toBe(
      "sk-new-plaintext-cd34",
    );
    // 响应投影：hasKey=true + 尾号，明文不回显。
    expect(await screen.findByText("新条目")).toBeInTheDocument();
    expect(screen.getByLabelText("provider new-prov").textContent).toContain("尾号 ••••cd34");
    expect(screen.queryByText("sk-new-plaintext-cd34")).not.toBeInTheDocument();
  });
});
describe("ModelsPage 答疑 LLM 卡 legacy 态编辑（028 AC-4）", () => {
  beforeEach(() => {
    resetApiState();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  it("028 AC-4: legacy 态 inline 编辑模型/端点保存——PUT 携带表单当前值（状态型字段语义），保存后徽标换「自定义」且回显新值", async () => {
    const observed = stubAdminLlmFetch(CONFIGURED_ON);

    renderModelsPage();

    const user = userEvent.setup();
    const modelInput = await screen.findByLabelText("当前模型");
    const endpointInput = screen.getByLabelText("生效端点");
    // 默认回显生效值（覆盖 > env）。
    expect(modelInput).toHaveValue("glm-4.7");
    expect(endpointInput).toHaveValue("https://open.bigmodel.cn/api/paas/v4");

    await user.clear(modelInput);
    await user.type(modelInput, "glm-5.3-flash");
    await user.clear(endpointInput);
    await user.type(endpointInput, "http://10.0.0.8:8080/v4");
    await user.click(screen.getByRole("button", { name: "保存答疑配置" }));

    // 请求形状：enabled 保持 + 端点/模型当前值；密钥留空=不变更（不携带 apiKey 字段）。
    await waitFor(() => expect(observed.putBodies).toHaveLength(1));
    expect(observed.putBodies[0]).toEqual({
      enabled: true,
      baseUrl: "http://10.0.0.8:8080/v4",
      model: "glm-5.3-flash",
    });
    expect(JSON.stringify(observed.putBodies[0])).not.toContain("apiKey");
    // 乐观刷新：徽标换「自定义」，输入框回显保存后的值，提示即时生效。
    expect(await screen.findByRole("status")).toHaveTextContent(/答疑配置已保存并即时生效/);
    await waitFor(() => expect(screen.getAllByText("自定义")).toHaveLength(1));
    await waitFor(() => expect(screen.getByLabelText("当前模型")).toHaveValue("glm-5.3-flash"));
    await waitFor(() =>
      expect(screen.getByLabelText("生效端点")).toHaveValue("http://10.0.0.8:8080/v4"),
    );
  });

  it("028 AC-4: 密钥写-only——password 掩码、留空=不变更不携带字段；填入=加密入库投影尾号且明文不残留 DOM", async () => {
    const observed = stubAdminLlmFetch(CONFIGURED_ON);

    renderModelsPage();

    const user = userEvent.setup();
    const keyInput = await screen.findByLabelText("答疑 API 密钥");
    // 写-only：password 掩码、初始空白（已存密钥/环境变量密钥不回填）。
    expect(keyInput).toHaveAttribute("type", "password");
    expect(keyInput).toHaveValue("");

    // 留空保存：PUT 不携带 apiKey（=不变更）。
    await user.click(screen.getByRole("button", { name: "保存答疑配置" }));
    await waitFor(() => expect(observed.putBodies).toHaveLength(1));
    expect(observed.putBodies[0]).not.toHaveProperty("apiKey");

    // 填入新密钥保存：body 携带明文（写-only 请求侧允许），响应只投影尾号。
    await user.type(screen.getByLabelText("答疑 API 密钥"), "sk-tutor-plaintext-99ab");
    await user.click(screen.getByRole("button", { name: "保存答疑配置" }));
    await waitFor(() => expect(observed.putBodies).toHaveLength(2));
    expect((observed.putBodies[1] as Record<string, unknown>).apiKey).toBe(
      "sk-tutor-plaintext-99ab",
    );
    // 保存成功后输入框清空：明文不留在 DOM（无明文回显红线），尾号进入提示。
    await waitFor(() => expect(screen.getByLabelText("答疑 API 密钥")).toHaveValue(""));
    expect(screen.getByText(/已存密钥尾号 ••••99ab/)).toBeInTheDocument();
    expect(screen.queryByText("sk-tutor-plaintext-99ab")).not.toBeInTheDocument();
  });

  it("028 AC-4: 「重置为环境默认」需确认——确认后 PUT 清覆盖（空串）+ clearKey 删 tutor 密钥，徽标回「环境变量」", async () => {
    const observed = stubAdminLlmFetch({
      ...CONFIGURED_ON,
      source: "custom",
      keyLast4: "8811",
    });

    renderModelsPage();

    const user = userEvent.setup();
    // 覆盖态徽标在位。
    expect(await screen.findByText("自定义")).toBeInTheDocument();
    expect(screen.getByText(/已存密钥尾号 ••••8811/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "重置为环境默认" }));
    // 确认弹窗讲清后果（清覆盖 + 删答疑密钥 + 开关不变）。
    expect(await screen.findByRole("dialog")).toHaveTextContent(/清除端点与模型的覆盖值/);
    expect(screen.getByRole("dialog")).toHaveTextContent(/答疑开关保持不变/);

    await user.click(screen.getByRole("button", { name: "重置" }));
    await waitFor(() => expect(observed.putBodies).toHaveLength(1));
    expect(observed.putBodies[0]).toEqual({
      enabled: true,
      baseUrl: "",
      model: "",
      clearKey: true,
    });
    // 重置后徽标回「环境变量」：生效值回退环境变量。
    expect(await screen.findByRole("status")).toHaveTextContent(/回退环境变量/);
    await waitFor(() => expect(screen.getAllByText("环境变量").length).toBeGreaterThanOrEqual(1));
    expect(screen.queryByText("自定义")).not.toBeInTheDocument();
  });

  it("028 AC-4: 来源徽标三态——目录激活=「目录」（字段指引走抽屉、无 inline 编辑）；覆盖态=「自定义」；纯 env=「环境变量」", async () => {
    // 目录激活态：徽标「目录」，无 inline 输入，指引指向抽屉。
    const observed = stubAdminLlmFetch(
      { ...CONFIGURED_ON, providerId: "zcode-gateway", protocol: "anthropic-messages" },
      { providers: [ZCODE_GATEWAY] },
    );
    const first = renderModelsPage();

    expect(await screen.findByText("目录")).toBeInTheDocument();
    expect(screen.queryByLabelText("当前模型")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("答疑 API 密钥")).not.toBeInTheDocument();
    expect(screen.getByText(/在上方 provider 卡「编辑」抽屉中修改/)).toBeInTheDocument();
    // 目录态无保存/重置按钮（编辑走抽屉）。
    expect(screen.queryByRole("button", { name: "保存答疑配置" })).toBeNull();
    expect(screen.queryByRole("button", { name: "重置为环境默认" })).toBeNull();
    first.unmount();

    // 覆盖态：source=custom → 徽标「自定义」。
    stubAdminLlmFetch({ ...CONFIGURED_ON, source: "custom", keyLast4: "8811" });
    const second = renderModelsPage();
    expect(await screen.findByText("自定义")).toBeInTheDocument();
    second.unmount();

    // 纯 env 态：source=env → 徽标「环境变量」。
    stubAdminLlmFetch(CONFIGURED_ON);
    const third = renderModelsPage();
    expect(await screen.findByText("环境变量")).toBeInTheDocument();
    third.unmount();
    expect(observed.getCalls).toBeGreaterThanOrEqual(0);
  });

  it("028 AC-4: 编辑态下外部变更不抹输入（dirty 守卫）——有未保存输入时服务端状态回填不覆盖表单", async () => {
    const observed = stubAdminLlmFetch(CONFIGURED_ON);
    const queryClient = createQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <ModelsPage />
      </QueryClientProvider>,
    );

    const user = userEvent.setup();
    const modelInput = await screen.findByLabelText("当前模型");
    await user.clear(modelInput);
    await user.type(modelInput, "glm-5.3-flash");

    // 模拟一次外部变更落缓存（如目录激活切换等操作触发的 config 回填）。
    queryClient.setQueryData(queryKeys.admin.llmConfig, { ...CONFIGURED_ON, model: "glm-changed" });

    // dirty（有未保存输入）→ 表单不被服务端值覆盖；无输入时才按服务端值对齐。
    await waitFor(() => expect(screen.getByLabelText("当前模型")).toHaveValue("glm-5.3-flash"));
    expect(observed.getCalls).toBeGreaterThanOrEqual(0);
  });

  it("028 AC-4: model-only 覆盖（清端点保留模型）保存后徽标为「自定义」——与覆盖轨实际生效一致，不误报回退环境变量", async () => {
    const observed = stubAdminLlmFetch(CONFIGURED_ON);

    renderModelsPage();

    const user = userEvent.setup();
    await screen.findByLabelText("当前模型");
    // 清空端点、保留模型 → 保存后存储为 model-only 覆盖（服务端 source=custom）。
    await user.clear(screen.getByLabelText("生效端点"));
    await user.click(screen.getByRole("button", { name: "保存答疑配置" }));

    await waitFor(() => expect(observed.putBodies).toHaveLength(1));
    expect(observed.putBodies[0]).toEqual({
      enabled: true,
      baseUrl: "",
      model: "glm-4.7",
    });
    // 徽标与实际轨一致：任一覆盖存在即「自定义」（模型覆盖经 env 端点真实生效）。
    expect(await screen.findByRole("status")).toHaveTextContent(/答疑配置已保存并即时生效/);
    await waitFor(() => expect(screen.getAllByText("自定义")).toHaveLength(1));
  });
});
