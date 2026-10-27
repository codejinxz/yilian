package io.github.codejinxz.yilian.auth.service.impl;

import io.github.codejinxz.yilian.auth.dto.AccessToken;
import io.github.codejinxz.yilian.auth.dto.AuthResponse;
import io.github.codejinxz.yilian.auth.dto.ChangePasswordRequest;
import io.github.codejinxz.yilian.auth.dto.CsrfTokenResponse;
import io.github.codejinxz.yilian.auth.dto.CurrentUserResponse;
import io.github.codejinxz.yilian.auth.dto.LoginRequest;
import io.github.codejinxz.yilian.auth.dto.RegisterRequest;
import io.github.codejinxz.yilian.auth.dto.UserSettingsRequest;
import io.github.codejinxz.yilian.auth.dto.UserSettingsResponse;
import io.github.codejinxz.yilian.auth.service.AuthService;
import io.github.codejinxz.yilian.auth.service.JwtTokenService;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.AuthConstants;
import io.github.codejinxz.yilian.common.security.SecurityUtils;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.common.util.DateTimeUtils;
import io.github.codejinxz.yilian.common.util.PasswordUtils;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.dto.RegisterCommand;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 认证用例实现：短期 JWT access token + HttpOnly refresh 会话。
 *
 * <p>refresh token 由 Spring Session JDBC 保存，刷新时轮换 session id；登出与改密直接失效会话。 access token 不落库、不做黑名单，依靠
 * 15 分钟短有效期控制风险。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final CsrfTokenRepository csrfTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final FindByIndexNameSessionRepository<? extends Session> refreshSessions;

    /**
     * 依赖全部面向接口，便于测试时替换账号查询、token 签发与会话吊销。
     *
     * @param userAccountService 账号服务
     * @param passwordEncoder 密码比对器
     * @param csrfTokenRepository CSRF 令牌仓库
     * @param jwtTokenService access token 签发服务
     * @param refreshSessions 按用户名索引的 refresh 会话仓库
     */
    public AuthServiceImpl(
            UserAccountService userAccountService,
            PasswordEncoder passwordEncoder,
            CsrfTokenRepository csrfTokenRepository,
            JwtTokenService jwtTokenService,
            FindByIndexNameSessionRepository<? extends Session> refreshSessions) {
        this.userAccountService = userAccountService;
        this.passwordEncoder = passwordEncoder;
        this.csrfTokenRepository = csrfTokenRepository;
        this.jwtTokenService = jwtTokenService;
        this.refreshSessions = refreshSessions;
    }

    @Override
    public CsrfTokenResponse issueCsrfToken(
            HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadDeferredToken(request, response).get();
        return new CsrfTokenResponse(
                token.getToken(), token.getHeaderName(), token.getParameterName());
    }

    @Override
    public AuthResponse login(
            LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (PasswordUtils.exceedsBcryptLimit(request.password())) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "密码过长（最多 72 字节）");
        }

        UserCredentials user =
                userAccountService
                        .findByLogin(request.username())
                        .filter(candidate -> candidate.enabled() && !candidate.deleted())
                        .orElseThrow(() -> new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS);
        }

        startSession(httpRequest, user);
        userAccountService.recordLogin(user.id());
        return issue(user);
    }

    @Override
    public AuthResponse register(
            RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        UserCredentials user =
                userAccountService.register(
                        new RegisterCommand(
                                request.email(),
                                request.username(),
                                request.password(),
                                request.inviteCode()));
        startSession(httpRequest, user);
        return issue(user);
    }

    @Override
    public AuthResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            throw new BusinessException(ApiCode.AUTH_REQUIRED);
        }
        Object refreshUserId = session.getAttribute(AuthConstants.REFRESH_USER_ID_ATTRIBUTE);
        if (!(refreshUserId instanceof Long userId)) {
            invalidateQuietly(session);
            throw new BusinessException(ApiCode.AUTH_REQUIRED);
        }
        if (!isWithinAbsoluteTtl(session)) {
            invalidateQuietly(session);
            throw new BusinessException(ApiCode.AUTH_REQUIRED);
        }
        UserCredentials user =
                userAccountService
                        .findById(userId)
                        .filter(candidate -> candidate.enabled() && !candidate.deleted())
                        .orElseThrow(
                                () -> {
                                    invalidateQuietly(session);
                                    return new BusinessException(ApiCode.AUTH_REQUIRED);
                                });
        rotateSessionId(httpRequest);
        return issue(user);
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String username = SecurityUtils.currentUsername().orElse(null);
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object indexedName = session.getAttribute(AuthConstants.PRINCIPAL_NAME_ATTRIBUTE);
            if (indexedName instanceof String indexedUsername) {
                username = indexedUsername;
            }
            invalidateQuietly(session);
        }
        // 登出按用户吊销全部 refresh 会话，避免旧 cookie 或并发刷新把用户重新拉回。
        if (username != null) {
            revokeRefreshSessions(username);
        }
        SecurityContextHolder.clearContext();
    }

    @Override
    public CurrentUserResponse currentUser(YilianUserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ApiCode.AUTH_REQUIRED);
        }
        return CurrentUserResponse.from(principal);
    }

    @Override
    public void changePassword(
            YilianUserPrincipal principal,
            ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        long userId = requirePrincipal(principal).id();
        UserCredentials user =
                userAccountService
                        .findById(userId)
                        .filter(candidate -> candidate.enabled() && !candidate.deleted())
                        .orElseThrow(() -> new BusinessException(ApiCode.AUTH_REQUIRED));
        if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "当前密码不正确");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new BusinessException(ApiCode.WEAK_PASSWORD, "新密码不能与当前密码相同");
        }
        userAccountService.changePassword(userId, request.newPassword());
        revokeRefreshSessions(user.username());
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            invalidateQuietly(session);
        }
    }

    @Override
    public UserSettingsResponse getSettings(YilianUserPrincipal principal) {
        return UserSettingsResponse.from(
                userAccountService.getSettings(requirePrincipal(principal).id()));
    }

    @Override
    public UserSettingsResponse updateSettings(
            YilianUserPrincipal principal, UserSettingsRequest request) {
        return UserSettingsResponse.from(
                userAccountService.updateSettings(
                        requirePrincipal(principal).id(), request.toPatch()));
    }

    /**
     * 建立 refresh 会话；写 principal 索引以便按用户批量吊销。
     *
     * @param request 当前请求
     * @param user 已认证用户
     */
    private void startSession(HttpServletRequest request, UserCredentials user) {
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthConstants.REFRESH_USER_ID_ATTRIBUTE, user.id());
        session.setAttribute(AuthConstants.REFRESH_CREATED_AT_ATTRIBUTE, DateTimeUtils.now());
        session.setAttribute(AuthConstants.PRINCIPAL_NAME_ATTRIBUTE, user.username());
        rotateSessionId(request);
    }

    /**
     * 用账号视图重建 principal 并签发 access token，保证响应中的用户信息与 JWT 声明同源。
     *
     * @param user 已通过校验的账号视图
     * @return access token 与当前用户
     */
    private AuthResponse issue(UserCredentials user) {
        YilianUserPrincipal principal =
                YilianUserPrincipal.of(
                        user.id(),
                        user.username(),
                        user.role().name(),
                        user.enabled(),
                        user.passwordChanged());
        AccessToken accessToken = jwtTokenService.issue(user);
        return AuthResponse.from(accessToken, CurrentUserResponse.from(principal));
    }

    private static YilianUserPrincipal requirePrincipal(YilianUserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ApiCode.AUTH_REQUIRED);
        }
        return principal;
    }

    private void revokeRefreshSessions(String username) {
        refreshSessions.findByPrincipalName(username).keySet().forEach(refreshSessions::deleteById);
    }

    /**
     * 换发 session id；会话已被并发请求失效时按未登录处理，而不是继续复用旧 id。
     *
     * @param request 当前请求
     */
    private void rotateSessionId(HttpServletRequest request) {
        try {
            request.changeSessionId();
        } catch (IllegalStateException exception) {
            throw new BusinessException(ApiCode.AUTH_REQUIRED);
        }
    }

    /**
     * Spring Session 的 timeout 是滑动过期，这里额外用创建时间做绝对过期判断。
     *
     * @param session refresh 会话
     * @return 是否仍在绝对有效期内
     */
    private boolean isWithinAbsoluteTtl(HttpSession session) {
        Object createdAt = session.getAttribute(AuthConstants.REFRESH_CREATED_AT_ATTRIBUTE);
        if (!(createdAt instanceof Instant issuedAt)) {
            return false;
        }
        return !DateTimeUtils.isExpired(
                issuedAt, AuthConstants.REFRESH_ABSOLUTE_TTL, DateTimeUtils.now());
    }

    /**
     * 失效会话并忽略「已失效」异常，使登出与刷新失败路径可重复执行。
     *
     * @param session 待失效的会话
     */
    private void invalidateQuietly(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // 会话已被并发请求失效时保持幂等，不向客户端暴露内部状态。
        }
    }
}
