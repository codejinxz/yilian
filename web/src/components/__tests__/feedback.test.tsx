import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ErrorState } from "@/components/feedback";
import { ApiError } from "@/lib/api";

/**
 * ErrorState 语境分化（V11 修复回归）：
 * 练习页（默认 variant）保留离线队列文案——「已经填写的答案会保存在本机，联网后再提交」；
 * 管理端（variant="admin"）没有离线作答语义，标题统一「管理数据加载失败」、
 * 提示不带练习页队列文案。403 鉴权说明两种语境都成立，不分化。
 */

describe("ErrorState 语境分化", () => {
  it("练习页离线（默认）：保留「网络暂时不可用」与答案本机保存文案", () => {
    render(
      <ErrorState
        error={new ApiError(0, -1, "网络连接失败，请检查网络后重试", "transport")}
        onRetry={vi.fn()}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("网络暂时不可用");
    expect(screen.getByRole("alert")).toHaveTextContent("已经填写的答案会保存在本机，联网后再提交");
  });

  it("管理端离线：标题「管理数据加载失败」，提示检查网络/服务端，不带练习页队列文案", () => {
    render(
      <ErrorState
        error={new ApiError(0, -1, "网络连接失败，请检查网络后重试", "transport")}
        onRetry={vi.fn()}
        variant="admin"
      />,
    );

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("管理数据加载失败");
    expect(alert).toHaveTextContent("请检查网络连接或确认服务端已启动");
    expect(alert).not.toHaveTextContent("已经填写的答案会保存在本机");
  });

  it("管理端服务 5xx：标题同样为「管理数据加载失败」，服务端 message 透出", () => {
    render(
      <ErrorState
        error={new ApiError(500, 500, "服务响应异常，请稍后重试")}
        onRetry={vi.fn()}
        variant="admin"
      />,
    );

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("管理数据加载失败");
    expect(alert).toHaveTextContent("服务响应异常，请稍后重试");
    expect(alert).not.toHaveTextContent("已经填写的答案会保存在本机");
  });

  it("管理端 403：鉴权说明不变（两语境通用），标题不套「管理数据加载失败」", () => {
    render(
      <ErrorState
        error={new ApiError(403, 40300, "服务端拒绝访问")}
        onRetry={vi.fn()}
        variant="admin"
      />,
    );

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("服务端拒绝访问");
    expect(alert).toHaveTextContent("管理员接口由服务端强制鉴权");
    expect(alert).not.toHaveTextContent("管理数据加载失败");
  });
});
