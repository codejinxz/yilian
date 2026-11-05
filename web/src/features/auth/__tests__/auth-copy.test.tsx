import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LoginPage, RegisterPage } from "@/features/auth/LoginPage";
import { createQueryClient } from "@/lib/query";

/**
 * 027 AC-8 · 认证页数据叙事如实化。
 *
 * 真实数据流：登录/注册成功后 syncGuestAttemptsInBackground 会把本机游客答题记录自动并入
 * 账号（原始记录保留本机，未并入的可在设置页导出/重试）；但复习排程按账号侧队列计算，
 * 不随记录迁移——这是「刚注册账号今日/复习为 0」观感的真相。文案按此描述，
 * 不得出现与自动同步行为矛盾的「不会并入」，也不得有不带「自动」限定的并入承诺。
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

  /** 注册页：如实描述自动并入 + 本机保留 + 复习安排不随迁移，三个事实一个都不能少。 */
  it("registerCopyTruthful_ac8: 注册页如实描述自动并入与本机保留", async () => {
    renderAuth(<RegisterPage />);

    // 自动并入（与登录/注册成功后的后台同步行为一致）+ 原始记录保留本机。
    expect(await screen.findByText(/会自动并入账号/)).toBeInTheDocument();
    expect(screen.getByText(/原始记录仍保留在本机/)).toBeInTheDocument();
    // 部分迁移的真相写给用户：复习安排按账号重新排，解释「新账号复习为 0」的观感。
    expect(screen.getByText(/复习安排不随记录迁移/)).toBeInTheDocument();
    // 旧的不带「自动」限定的并入承诺不再出现。
    expect(screen.queryByText(/练习记录会并入账号/)).toBeNull();
  });

  /** 登录页：与注册页同一口径，不再有互相矛盾的话术。 */
  it("loginCopyTruthful_ac8: 登录页与自动同步行为同一口径", async () => {
    renderAuth(<LoginPage />);

    expect(await screen.findByText(/会自动并入账号/)).toBeInTheDocument();
    expect(screen.getByText(/复习安排不随记录迁移/)).toBeInTheDocument();
    // 旧文案「错题和进度跟着账号走」与修订前那句「不会自动并入」都不再出现。
    expect(screen.queryByText(/错题和进度跟着账号走/)).toBeNull();
    expect(screen.queryByText(/不会自动并入/)).toBeNull();
  });
});
