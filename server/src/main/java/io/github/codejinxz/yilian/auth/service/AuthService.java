package io.github.codejinxz.yilian.auth.service;

import io.github.codejinxz.yilian.auth.dto.AuthResponse;
import io.github.codejinxz.yilian.auth.dto.ChangePasswordRequest;
import io.github.codejinxz.yilian.auth.dto.CsrfTokenResponse;
import io.github.codejinxz.yilian.auth.dto.CurrentUserResponse;
import io.github.codejinxz.yilian.auth.dto.LoginRequest;
import io.github.codejinxz.yilian.auth.dto.RegisterRequest;
import io.github.codejinxz.yilian.auth.dto.UserSettingsRequest;
import io.github.codejinxz.yilian.auth.dto.UserSettingsResponse;
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
     * @param request 登录请求
     * @param httpRequest 当前请求
     * @param httpResponse 当前响应
     * @return access token 与当前用户
     */
    AuthResponse login(
            LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse);

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
}
