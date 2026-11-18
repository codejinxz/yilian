package io.github.codejinxz.yilian.auth.service;

import io.github.codejinxz.yilian.auth.dto.ChangePasswordRequest;
import io.github.codejinxz.yilian.auth.dto.DeleteAccountRequest;
import io.github.codejinxz.yilian.auth.dto.LoginRequest;
import io.github.codejinxz.yilian.auth.dto.LoginTotpRequest;
import io.github.codejinxz.yilian.auth.dto.RegisterRequest;
import io.github.codejinxz.yilian.auth.dto.UserSettingsRequest;
import io.github.codejinxz.yilian.auth.vo.AccountExportView;
import io.github.codejinxz.yilian.auth.vo.AuthResponse;
import io.github.codejinxz.yilian.auth.vo.CsrfTokenResponse;
import io.github.codejinxz.yilian.auth.vo.CurrentUserResponse;
import io.github.codejinxz.yilian.auth.vo.DeleteAccountReceiptView;
import io.github.codejinxz.yilian.auth.vo.UserSettingsResponse;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 认证与账号设置用例入口：CSRF 播种、登录、刷新、登出、注册、改密与当前用户设置。
 *
 * <p>实现见 {@code auth.service.impl.AuthServiceImpl}。
 */
public interface AuthService {

    /**
     * 播种可读的 CSRF cookie，并返回前端回填请求头所需的原始 token。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @return CSRF 令牌信息
     */
    CsrfTokenResponse issueCsrfToken(HttpServletRequest request, HttpServletResponse response);

    /**
     * 校验凭据并签发 access token，同时建立用于 refresh 的服务端会话。
     *
     * <p>账号启用两步验证时密码步不建会话：抛 40305 并在 data 携带 challengeToken， 由 {@link #loginTotp} 完成第二步。
     *
     * @param request 登录请求
     * @param httpRequest 当前请求
     * @param httpResponse 当前响应
     * @return access token 与当前用户
     */
    AuthResponse login(
            LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse);

    /**
     * 登录第二步：凭 challengeToken + 动态码（TOTP 或恢复码）完成两步验证并签发 access token。
     *
     * @param request 挑战 token 与 8 位验证码
     * @param httpRequest 当前请求
     * @param httpResponse 当前响应
     * @return access token 与当前用户；恢复码登录时响应携带剩余恢复码数
     */
    AuthResponse loginTotp(
            LoginTotpRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse);

    /**
     * 邀请码注册并直接登录。
     *
     * @param request 注册请求
     * @param httpRequest 当前请求
     * @param httpResponse 当前响应
     * @return access token 与当前用户
     */
    AuthResponse register(
            RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse);

    /**
     * 使用 HttpOnly refresh cookie 换取新的 access token，并轮换 refresh 会话。
     *
     * @param httpRequest 当前请求
     * @param httpResponse 当前响应
     * @return 新的 access token 与当前用户
     */
    AuthResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse);

    /**
     * 失效 refresh 会话并清理安全上下文。
     *
     * @param request 当前请求
     * @param response 当前响应
     */
    void logout(HttpServletRequest request, HttpServletResponse response);

    /**
     * 取当前登录用户。
     *
     * @param principal 当前 principal
     * @return 当前用户信息
     */
    CurrentUserResponse currentUser(YilianUserPrincipal principal);

    /**
     * 改密并吊销该用户全部 refresh 会话。
     *
     * @param principal 当前登录用户
     * @param request 改密请求
     * @param httpRequest 当前请求，用于吊销会话
     */
    void changePassword(
            YilianUserPrincipal principal,
            ChangePasswordRequest request,
            HttpServletRequest httpRequest);

    /**
     * 读取当前用户设置。
     *
     * @param principal 当前登录用户
     * @return 设置视图
     */
    UserSettingsResponse getSettings(YilianUserPrincipal principal);

    /**
     * 更新当前用户设置。
     *
     * @param principal 当前登录用户
     * @param request 待更新字段
     * @return 更新后的设置
     */
    UserSettingsResponse updateSettings(YilianUserPrincipal principal, UserSettingsRequest request);

    /**
     * 服务端账号数据导出：本人账号块（身份投影+设置）与练习域切片（attempts/判分/申诉/复习卡） 整包一次返回；不含密码哈希、TOTP 材料等凭据投影，不含他人数据。
     *
     * @param principal 当前登录用户
     * @return 导出视图（响应体为导出 JSON 本身，非统一信封）
     */
    AccountExportView exportAccountData(YilianUserPrincipal principal);

    /**
     * 自助删号：密码确认（开启 2FA 的用户还需动态码/恢复码）后软删账号、清 TOTP 注册、 吊销全部 refresh 会话；无自助撤销，30 天冷静期到期由日调度任务匿名化。
     *
     * @param principal 当前登录用户
     * @param request 密码与可选动态码
     * @return 回执（软删时刻与匿名化时点，UTC）
     */
    DeleteAccountReceiptView deleteAccount(
            YilianUserPrincipal principal, DeleteAccountRequest request);
}
