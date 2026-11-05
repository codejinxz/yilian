import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LoginPage, RegisterPage } from "@/features/auth/LoginPage";
import { createQueryClient } from "@/lib/query";

/**
 * 027 AC-8 · 认证页数据叙事如实化。
 *
 * 体验报告 D1/D2：注册页承诺「本机练习记录会并入账号」但账号侧今日/复习/统计为 0（承诺
 * 未兑现）；登录已有账号时本机视图被静默替换且无提示。文案改为如实描述——本机数据不自动
 * 并入账号、登录后页面显示账号数据、原始记录保留本机并可在设置页导出/手动同步；
 * 导入功能另批实现，本批不承诺。
 */

const api = vi.hoisted(() => ({
  getCaptcha: vi.fn(),
  login: vi.fn(),
  register: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api,
  // lib/query 等兄弟模块按命名导入这些符号；测试路径不会真正调用。
  ApiError: class ApiError extends Error {},
  getAccessToken: vi.fn(() => null),
  resetApiState: vi.fn(),
  setAccessToken: vi.fn(),
  isGuestSession: vi.fn(() => true),
  setAuthStateProbe: vi.fn(),
  setUnauthorizedHandler: vi.fn(),
}));

const guestUpgrade = vi.hoisted(() => ({
  syncGuestAttemptsInBackground: vi.fn(),
}));

vi.mock("@/lib/api/guest-upgrade", () => guestUpgrade);

/** 最小 router 桩：认证页只用 Link（页签）与 useNavigate。 */
vi.mock("@tanstack/react-router", () => ({
  Link: (props: { to?: string; className?: string; children?: ReactNode }) => (
    <a href="#stub" className={props.className}>
      {props.children}
    </a>
  ),
  useNavigate: () => async () => {},
}));

function renderAuth(ui: ReactNode) {
  return render(<QueryClientProvider client={createQueryClient()}>{ui}</QueryClientProvider>);
}

describe("认证页数据叙事文案（027 AC-8）", () => {
  beforeEach(() => {
    window.localStorage.clear();
    api.getCaptcha.mockReset();
    api.getCaptcha.mockResolvedValue({ captchaId: "c1", imageBase64: "cG5n", enabled: false });
    api.login.mockReset();
    api.register.mockReset();
  });

  /** 注册页：不再承诺「会并入账号」，如实说明不自动并入 + 本机保留 + 设置页出口。 */
  it("registerCopyTruthful_ac8: 注册页不再承诺本机记录自动并入账号", async () => {
    renderAuth(<RegisterPage />);

    // 旧承诺（D1 的「承诺未兑现」源头）必须消失。
    expect(screen.queryByText(/练习记录会并入账号/)).toBeNull();
    // 注册补充说明：不自动并入 + 原始记录保留本机 + 设置页导出/手动同步出口。
    const registerNote = await screen.findByText(/注册后账号从零开始/);
    expect(registerNote.textContent).toContain("不会自动并入账号");
    expect(registerNote.textContent).toContain("仍保留在这台设备");
    expect(registerNote.textContent).toContain("手动同步到账号");
  });

  /** 登录页：如实说明数据视图切换（D2 的静默替换现有了预告）与游客记录去向。 */
  it("loginCopyTruthful_ac8: 登录页如实说明账号数据视图与游客记录去向", async () => {
    renderAuth(<LoginPage />);

    // 登录后显示账号的数据（视图切换不再静默）。
    expect(await screen.findByText(/显示账号的数据/)).toBeInTheDocument();
    expect(screen.getByText(/不再出现在这些页面/)).toBeInTheDocument();
    expect(screen.getByText(/可在设置页导出/)).toBeInTheDocument();
    // 旧文案「错题和进度跟着账号走」的模糊承诺不再出现。
    expect(screen.queryByText(/错题和进度跟着账号走/)).toBeNull();
  });
});
