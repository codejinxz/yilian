import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TotpCard } from "@/features/settings/TotpCard";
import { resetApiState } from "@/lib/api";
import { createQueryClient } from "@/lib/query";

/**
 * 设置页两步验证卡片（[AC-4]）交互测试：状态展示 → 开启流程（密码确认 → 密钥/otpauth
 * 展示与复制 → 激活 → 恢复码一次性展示与已抄写确认）→ 关闭双确认入口。fetch 桩按 API §4.10 真实形状应答。
 */

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

function ok(data: unknown): { code: number; message: string; data: unknown } {
  return { code: 0, message: "success", data };
}

/** 构造状态桩；后续用例在激活后切换返回值模拟服务端状态变化。 */
function stubTotpApi(enabledInitial: boolean) {
  let enabled = enabledInitial;
  const calls: Array<{ path: string; body: Record<string, unknown> }> = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost").pathname;
    if (url === "/api/auth/2fa/status" && (init?.method ?? "GET") === "GET") {
      return jsonResponse(
        ok({
          enabled,
          enrolledAt: enabled ? "2026-11-18T00:00:00Z" : null,
          remainingRecoveryCodes: enabled ? 10 : 0,
        }),
      );
    }
    if (url === "/api/auth/2fa/setup" && init?.method === "POST") {
      calls.push({ path: "setup", body: JSON.parse(String(init.body ?? "{}")) });
      return jsonResponse(
        ok({
          secret: "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
          otpauthUri:
            "otpauth://totp/Yilian:admin?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Yilian&algorithm=SHA1&digits=8&period=30",
        }),
      );
    }
    if (url === "/api/auth/2fa/activate" && init?.method === "POST") {
      calls.push({ path: "activate", body: JSON.parse(String(init.body ?? "{}")) });
      enabled = true;
      return jsonResponse(
        ok({
          recoveryCodes: [
            "10101010",
            "20202020",
            "30303030",
            "40404040",
            "50505050",
            "60606060",
            "70707070",
            "80808080",
            "90909090",
            "11223344",
          ],
        }),
      );
    }
    if (url === "/api/auth/2fa/disable" && init?.method === "POST") {
      calls.push({ path: "disable", body: JSON.parse(String(init.body ?? "{}")) });
      enabled = false;
      return jsonResponse(ok(null));
    }
    return jsonResponse({ code: 40400, message: "请求的资源不存在", data: null }, 404);
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls };
}

function renderCard() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <TotpCard />
    </QueryClientProvider>,
  );
}

describe("TotpCard 设置页两步验证卡片", () => {
  beforeEach(() => {
    resetApiState();
    vi.unstubAllGlobals();
    // biome-ignore lint/suspicious/noDocumentCookie: 测试需要写入可读的 CSRF cookie
    document.cookie = "XSRF-TOKEN=test-token; path=/";
    // clipboard 在 jsdom 不存在：复制按钮走降级提示分支，不阻塞主流程断言。
  });

  /** [AC-4] 开启流程：密码确认 → setup 密钥/URI 展示 → 激活 → 恢复码一次性展示 → 已抄写关闭。 */
  it("enrollsThroughSetupActivateAndShowsRecoveryCodesOnce_ac4", async () => {
    const { calls } = stubTotpApi(false);
    const view = renderCard();
    const user = userEvent.setup();

    expect(await screen.findByText(/未开启/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "开启两步验证" }));

    // 密码确认 → setup：密钥与 otpauth 文本出现（复制按钮存在，不伪造点击成功）。
    await user.type(screen.getByLabelText("当前密码"), "current-password");
    await user.click(screen.getByRole("button", { name: "生成密钥" }));
    expect(await screen.findByText(/手动录入密钥/)).toBeInTheDocument();
    expect(screen.getByText("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "复制 otpauth 文本" })).toBeInTheDocument();
    expect(calls.find((call) => call.path === "setup")?.body).toEqual({
      password: "current-password",
    });

    // 激活：10 个恢复码一次性展示，警示条出现；状态翻转（桩内 enabled=true）。
    await user.type(screen.getByLabelText("验证器当前 8 位动态码"), "12345678");
    await user.click(screen.getByRole("button", { name: "验证并开启" }));
    expect(await screen.findByText(/我已抄写保存/)).toBeInTheDocument();
    expect(screen.getByText("10101010")).toBeInTheDocument();
    expect(screen.getByText("90909090")).toBeInTheDocument();
    expect(calls.find((call) => call.path === "activate")?.body).toEqual({ code: "12345678" });

    // 已抄写确认后回到 idle：状态变为已开启，恢复码不再常驻（一次性语义）。
    await user.click(screen.getByRole("button", { name: "我已抄写保存" }));
    expect(await screen.findByText(/已开启。登录需要验证器动态码/)).toBeInTheDocument();
    expect(screen.queryByText("10101010")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "关闭两步验证" })).toBeInTheDocument();
    view.unmount();
  });

  /** [AC-4] 关闭流程：双确认表单（密码 + 当前动态码）提交 disable 端点。 */
  it("disableRequiresPasswordAndCode_ac4", async () => {
    const { calls } = stubTotpApi(true);
    const view = renderCard();
    const user = userEvent.setup();

    expect(await screen.findByText(/已开启。登录需要验证器动态码/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "关闭两步验证" }));

    await user.type(screen.getByLabelText("当前密码"), "current-password");
    await user.type(screen.getByLabelText("当前动态码（或恢复码）"), "12345678");
    await user.click(screen.getByRole("button", { name: "确认关闭" }));

    // 关闭成功：状态回未开启（桩内 enabled=false），关闭表单收起。
    expect(await screen.findByText(/未开启/)).toBeInTheDocument();
    expect(calls.find((call) => call.path === "disable")?.body).toEqual({
      password: "current-password",
      code: "12345678",
    });
    view.unmount();
  });
});
