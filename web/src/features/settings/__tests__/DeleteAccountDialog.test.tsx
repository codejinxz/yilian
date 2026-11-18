import { QueryClientProvider } from "@tanstack/react-query";
import { within } from "@testing-library/dom";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DeleteAccountDialog } from "@/features/settings/delete-account-dialog";
import { ApiError } from "@/lib/api-error";
import { createQueryClient } from "@/lib/query";
import { accountExportSchema } from "@/lib/schemas/account";

/**
 * 删号确认弹窗交互测试：渲染（风险说明/匿名化时点/密码位）→ 导出触发 → 确认流
 * （第一次确认 → 不可撤销二次确认 → 请求发出）→ 2FA 分支（动态码位显隐）→ 失败分支。
 * 另含 mock handlers 的 §4.11/§4.12 形状对照（handleMockRequest 直驱，zod 校验）。
 */

const accountApi = vi.hoisted(() => ({
  deleteAccount: vi.fn(),
  exportAccountData: vi.fn(),
}));

vi.mock("@/lib/api/account", () => accountApi);

const totpApi = vi.hoisted(() => ({
  getTotpStatus: vi.fn(),
}));

vi.mock("@/lib/api/totp", () => totpApi);

function renderDialog(onDeleted = vi.fn()) {
  const onClose = vi.fn();
  const result = render(
    <QueryClientProvider client={createQueryClient()}>
      <DeleteAccountDialog open={true} onClose={onClose} onDeleted={onDeleted} />
    </QueryClientProvider>,
  );
  return { onClose, onDeleted, result };
}

beforeEach(() => {
  vi.clearAllMocks();
  totpApi.getTotpStatus.mockResolvedValue({
    enabled: false,
    enrolledAt: null,
    remainingRecoveryCodes: 0,
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  // 下载桩在用例内按需安装；用例结束移除，避免污染兄弟用例。
  vi.unstubAllGlobals();
});

describe("DeleteAccountDialog 渲染与文案（036 AC-5）", () => {
  it("渲染风险说明、30 天匿名化时点、密码位与导出入口；未开 2FA 时无动态码位", async () => {
    renderDialog();

    expect(screen.getByRole("dialog", { name: "删除账号" })).toBeTruthy();
    expect(screen.getByText(/进入 30 天冷静期/)).toBeTruthy();
    expect(screen.getByText(/匿名化处理/)).toBeTruthy();
    expect(screen.getByText(/无自助撤销/)).toBeTruthy();
    expect(screen.getByText(/先导出我的数据（服务端）/)).toBeTruthy();
    expect(screen.getByLabelText("当前密码")).toBeTruthy();
    expect(screen.queryByLabelText(/验证器当前 8 位动态码/)).toBeNull();
    // 匿名化时点 = 今天 + 30 天（ISO 日期出现在说明文案里）。
    const in30Days = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10);
    expect(screen.getByText(new RegExp(in30Days))).toBeTruthy();
    await waitFor(() => expect(totpApi.getTotpStatus).toHaveBeenCalled());
  });

  it("2FA 开启时显示动态码位", async () => {
    totpApi.getTotpStatus.mockResolvedValue({
      enabled: true,
      enrolledAt: "2026-11-01T00:00:00Z",
      remainingRecoveryCodes: 8,
    });

    renderDialog();

    await waitFor(() => expect(screen.getByLabelText(/验证器当前 8 位动态码/)).toBeTruthy());
  });
});

describe("DeleteAccountDialog 确认流（036 AC-5）", () => {
  /** 页内全部 alert 文案合并（Notice 的 danger 提示与弹窗错误行都是 role=alert，合并断言）。 */
  function alertText(): string {
    return screen
      .getAllByRole("alert")
      .map((node) => node.textContent ?? "")
      .join("\n");
  }

  it("空密码第一次确认被拦下，不发请求", async () => {
    renderDialog();

    await userEvent.click(screen.getByRole("button", { name: "删除账号" }));

    expect(alertText()).toContain("请输入当前密码");
    expect(accountApi.deleteAccount).not.toHaveBeenCalled();
    // 二次确认对话框未出现。
    expect(screen.queryByText("确认删除账号？")).toBeNull();
  });

  it("密码确认 → 二次确认 → 发出删除请求；成功回调 onDeleted 并关闭弹窗", async () => {
    const onDeleted = vi.fn();
    accountApi.deleteAccount.mockResolvedValue({
      deletedAtUtc: "2026-11-19T00:00:00Z",
      anonymizeAtUtc: "2026-12-19T00:00:00Z",
    });
    renderDialog(onDeleted);

    await userEvent.type(screen.getByLabelText("当前密码"), "learner123");
    await userEvent.click(screen.getByRole("button", { name: "删除账号" }));

    // 第一次确认只弹二次确认，不发请求。
    expect(accountApi.deleteAccount).not.toHaveBeenCalled();
    const finalConfirm = screen.getByRole("dialog", { name: "确认删除账号？" });
    expect(finalConfirm.textContent).toContain("不可逆");

    await userEvent.click(
      within(finalConfirm as HTMLElement).getByRole("button", { name: "确认删除" }),
    );

    await waitFor(() =>
      expect(accountApi.deleteAccount).toHaveBeenCalledWith({ password: "learner123" }),
    );
    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
  });

  it("2FA 开启时缺动态码被拦下；带动态码请求透传", async () => {
    totpApi.getTotpStatus.mockResolvedValue({
      enabled: true,
      enrolledAt: "2026-11-01T00:00:00Z",
      remainingRecoveryCodes: 8,
    });
    accountApi.deleteAccount.mockResolvedValue({
      deletedAtUtc: "2026-11-19T00:00:00Z",
      anonymizeAtUtc: "2026-12-19T00:00:00Z",
    });
    renderDialog();

    await userEvent.type(screen.getByLabelText("当前密码"), "learner123");
    await userEvent.click(screen.getByRole("button", { name: "删除账号" }));
    expect(alertText()).toContain("请输入当前动态码");
    expect(accountApi.deleteAccount).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText(/验证器当前 8 位动态码/), "12345678");
    await userEvent.click(screen.getByRole("button", { name: "删除账号" }));
    await userEvent.click(
      within(screen.getByRole("dialog", { name: "确认删除账号？" }) as HTMLElement).getByRole(
        "button",
        { name: "确认删除" },
      ),
    );

    await waitFor(() =>
      expect(accountApi.deleteAccount).toHaveBeenCalledWith({
        password: "learner123",
        totpCode: "12345678",
      }),
    );
  });

  it("服务端拒绝（40101 错误密码）时错误透出，弹窗保持打开", async () => {
    accountApi.deleteAccount.mockRejectedValue(new ApiError(401, 40101, "当前密码不正确"));
    renderDialog();

    await userEvent.type(screen.getByLabelText("当前密码"), "wrong-pass");
    await userEvent.click(screen.getByRole("button", { name: "删除账号" }));
    await userEvent.click(
      within(screen.getByRole("dialog", { name: "确认删除账号？" }) as HTMLElement).getByRole(
        "button",
        { name: "确认删除" },
      ),
    );

    await waitFor(() => expect(alertText()).toContain("当前密码不正确"));
    // 弹窗仍在（可以改密码重试），onDeleted 未触发。
    expect(screen.getByRole("dialog", { name: "删除账号" })).toBeTruthy();
  });
});

describe("DeleteAccountDialog 服务端导出（036 AC-5）", () => {
  it("导出按钮触发 exportAccountData 并成功提示；失败时错误透出不阻断", async () => {
    accountApi.exportAccountData.mockResolvedValueOnce({
      schemaVersion: 1,
      exportedAtUtc: "2026-11-19T00:00:00Z",
      account: {
        id: "2",
        username: "learner",
        email: "learner@example.com",
        createdAtUtc: "2026-11-01T00:00:00Z",
        settings: {
          timezone: "Asia/Shanghai",
          dailyGoal: 3,
          ratingStandard: "TEM8",
          desiredRetention: 0.9,
          theme: "paper",
          notificationsEnabled: false,
        },
      },
      attempts: [],
      attemptGradings: [],
      appeals: [],
      reviewCards: [],
    });

    const createObjectUrl = vi.fn(() => "blob:mock");
    const revokeObjectUrl = vi.fn();
    const clicks: Array<HTMLAnchorElement> = [];
    vi.stubGlobal("URL", {
      ...globalThis.URL,
      createObjectURL: createObjectUrl,
      revokeObjectURL: revokeObjectUrl,
    });
    const originalCreateElement = document.createElement.bind(document);
    vi.spyOn(document, "createElement").mockImplementation((tag, options) => {
      if (tag === "a") {
        const anchor = originalCreateElement("a", options);
        anchor.click = () => clicks.push(anchor);
        return anchor;
      }
      return originalCreateElement(tag, options);
    });

    renderDialog();

    await userEvent.click(screen.getByRole("button", { name: /先导出我的数据（服务端）/ }));

    await waitFor(() =>
      expect(screen.getByText(/已下载服务端账号数据/).textContent).toContain("0 条答题"),
    );
    expect(accountApi.exportAccountData).toHaveBeenCalledTimes(1);
    expect(createObjectUrl).toHaveBeenCalled();
    expect(clicks).toHaveLength(1);
    expect(clicks[0]?.download).toMatch(/^yilian-export-\d{4}-\d{2}-\d{2}\.json$/);
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:mock");

    // 失败分支：错误行内透出，不阻断删号表单。
    accountApi.exportAccountData.mockRejectedValueOnce(new ApiError(401, 40100, "请先登录"));
    await userEvent.click(screen.getByRole("button", { name: /先导出我的数据（服务端）/ }));
    await waitFor(() => expect(screen.getByText(/导出失败：请先登录/)).toBeTruthy());
  });
});

// ---------------------------------------------------------------------------
// mock handlers 形状对照（§4.11/§4.12）：handleMockRequest 直驱，zod 校验顶层形状。
// ---------------------------------------------------------------------------

import { getMockDb, resetMockDb } from "@/mocks/db";
import { handleMockRequest } from "@/mocks/handlers";

describe("mock handlers §4.11/§4.12 形状对照（036 AC-5）", () => {
  beforeEach(() => {
    resetMockDb();
    vi.unstubAllGlobals();
  });

  it("/me/export 返回符合 accountExportSchema 的裸导出 JSON（非信封）", async () => {
    // 登录演示学习者（种子 attempts 归属该账号，导出应包含其答题切片）。
    const login = (await handleMockRequest("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username: "learner", password: "learner123" }),
    })) as { user: { id: string; username: string } };

    const data = (await handleMockRequest("/me/export", {})) as Record<string, unknown>;

    const parsed = accountExportSchema.safeParse(data);
    expect(parsed.success).toBe(true);
    expect(data.schemaVersion).toBe(1);
    expect(data.envelope).toBeUndefined();
    const account = data.account as { username: string; id: string };
    expect(account.username).toBe("learner");
    expect(account.id).toBe(login.user.id);
    // 演示学习者种子答题记录应随导出返回（形状对照的数据面）。
    expect((data.attempts as unknown[]).length).toBeGreaterThan(0);
    expect((data.attemptGradings as unknown[]).length).toBeGreaterThan(0);
  });

  it("/auth/delete-account：密码错误 40101；正确后软删清 2FA 并返回回执；重复调用 40100", async () => {
    await handleMockRequest("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username: "learner", password: "learner123" }),
    });

    await expect(
      handleMockRequest("/auth/delete-account", {
        method: "POST",
        body: JSON.stringify({ password: "wrong-password" }),
      }),
    ).rejects.toMatchObject({ code: 40101 });

    const receipt = (await handleMockRequest("/auth/delete-account", {
      method: "POST",
      body: JSON.stringify({ password: "learner123" }),
    })) as { deletedAtUtc: string; anonymizeAtUtc: string };

    expect(new Date(receipt.deletedAtUtc).getTime()).toBeLessThan(
      new Date(receipt.anonymizeAtUtc).getTime(),
    );
    // 匿名化时点 = 删号 + 30 天（演示态毫秒级误差容忍）。
    expect(
      new Date(receipt.anonymizeAtUtc).getTime() - new Date(receipt.deletedAtUtc).getTime(),
    ).toBeGreaterThanOrEqual(30 * 86_400_000 - 1000);

    const user = getMockDb().users.find((item) => item.username === "learner");
    expect(user?.deleted).toBe(true);
    expect(user?.totpEnabled).toBe(false);
    expect(getMockDb().sessionUserId).toBeNull();

    // 已删后重复调用：mock 会话已清 → 40100（与真实服务端 40900 语义分层，mock 以会话为准）。
    await expect(
      handleMockRequest("/auth/delete-account", {
        method: "POST",
        body: JSON.stringify({ password: "learner123" }),
      }),
    ).rejects.toMatchObject({ code: 40100 });
  });

  it("/auth/delete-account：2FA 开启时缺动态码 40000", async () => {
    await handleMockRequest("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username: "learner", password: "learner123" }),
    });
    const db = getMockDb();
    const learner = db.users.find((item) => item.username === "learner");
    if (!learner) {
      throw new Error("种子用户 learner 不存在");
    }
    learner.totpEnabled = true;

    await expect(
      handleMockRequest("/auth/delete-account", {
        method: "POST",
        body: JSON.stringify({ password: "learner123" }),
      }),
    ).rejects.toMatchObject({ code: 40000 });
  });
});
