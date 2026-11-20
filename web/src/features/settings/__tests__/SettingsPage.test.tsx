import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsPage } from "@/features/settings/SettingsPage";
import { useSettingsStore } from "@/features/settings/store";
import { ApiError } from "@/lib/api-error";
import { createQueryClient } from "@/lib/query";

/**
 * 批改方式开关「只存不生效」的演示语义。
 *
 * 本组用例锁定的事实：gradingMode 仅由 SettingsPage 读写（渲染选中态 +
 * toast），判分链路（练习页本地判分、提交接口、服务端 Grader）没有任何读取点；它是给
 * 深度批改预留的本地偏好，服务端也无对应的「本地/服务端判分切换」能力。开关按演示语义处理：行内「演示预留」说明 + 选项描述改为将来时 + toast 说明当前仍按本地判分执行。
 */

const api = vi.hoisted(() => ({
  me: vi.fn(),
  logout: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api,
  ApiError: class ApiError extends Error {},
  getAccessToken: vi.fn(() => null),
  resetApiState: vi.fn(),
  setAccessToken: vi.fn(),
  isGuestSession: vi.fn(() => true),
  setAuthStateProbe: vi.fn(),
  setUnauthorizedHandler: vi.fn(),
}));

const accountApi = vi.hoisted(() => ({
  updateSettings: vi.fn(),
  getSettings: vi.fn(),
}));

vi.mock("@/lib/api/account", () => accountApi);

const engine = vi.hoisted(() => ({
  getTodaySnapshot: vi.fn(),
  exportLocalData: vi.fn(),
  listAttempts: vi.fn(),
  clearLocalData: vi.fn(),
  resetPlacementState: vi.fn(),
  setDailyGoal: vi.fn(),
}));

vi.mock("@/lib/local-engine", () => engine);

vi.mock("@/lib/pwa", () => ({
  checkForUpdate: vi.fn(),
}));

/** 最小 router 桩：设置页只用 Link 与 useNavigate；真实 Link 需要路由上下文。 */
vi.mock("@tanstack/react-router", () => ({
  Link: (props: { to?: string; search?: unknown; className?: string; children?: ReactNode }) => (
    <a href="#stub" className={props.className}>
      {props.children}
    </a>
  ),
  useNavigate: () => async () => {},
}));

function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <SettingsPage />
    </QueryClientProvider>,
  );
}

describe("SettingsPage 批改方式如实标注（AC-9）", () => {
  beforeEach(() => {
    window.localStorage.clear();
    // 游客语义：me 401（无重试），批改方式行对游客与登录用户同样渲染。
    api.me.mockReset();
    api.me.mockRejectedValue(new ApiError(401, 40100, "请先登录"));
    engine.getTodaySnapshot.mockResolvedValue({ goal: 3 });
    engine.listAttempts.mockResolvedValue([]);
    // store 是模块级单例：显式回默认值，避免前序用例的选择泄漏。
    useSettingsStore.setState({ gradingMode: "AUTO", localOnly: false, theme: "paper" });
  });

  /** 行内「演示预留」说明常驻；切换只写本地 store（只存），说明不消失，toast 说明不生效。 */
  it("gradingModeSwitchStoresWithoutAffectingGrading_ac9", async () => {
    renderPage();

    // 「演示预留」说明出现，且「暂按自动执行」这类暗示选项间有差异的旧注不存在。
    expect(await screen.findByText(/演示预留：当前版本全部为本地判分/)).toBeInTheDocument();
    expect(screen.queryByText(/暂按「自动」执行/)).toBeNull();

    // 切换「总是模型」：只更新本地偏好（只存），行内说明保持，toast 明示当前仍按本地判分。
    await userEvent.setup().click(screen.getByRole("radio", { name: /总是模型/ }));
    expect(useSettingsStore.getState().gradingMode).toBe("ALWAYS_MODEL");
    expect(screen.getByText(/演示预留：当前版本全部为本地判分/)).toBeInTheDocument();
    expect(await screen.findByText(/演示：当前仍按本地判分执行/)).toBeInTheDocument();
  });

  /** 选项描述改为将来时语义，不再把「会调模型」描述成现状。 */
  it("gradingOptionDescriptionsAreFutureTense_ac9", async () => {
    renderPage();

    expect(await screen.findByText(/将来：先本地出结果，置信度低时再调模型/)).toBeInTheDocument();
    expect(screen.getByText(/将来：每次都走 LLM/)).toBeInTheDocument();
  });

  /** 数据与隐私段如实描述现状：当前没有模型调用发生。 */
  it("privacyCopyStatesLocalOnlyReality_ac9", async () => {
    renderPage();

    expect(
      await screen.findByText(/当前版本全部为本地判分，译文不会离开这台设备/),
    ).toBeInTheDocument();
    // 旧文案把「会发给模型供应商」写成现状（当前 DeepSeek），必须消失。
    expect(screen.queryByText(/当前 DeepSeek/)).toBeNull();
  });
});
