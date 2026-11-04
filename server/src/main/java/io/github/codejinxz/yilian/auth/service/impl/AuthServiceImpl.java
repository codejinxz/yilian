package io.github.codejinxz.yilian.auth.service.impl;

import io.github.codejinxz.yilian.auth.dto.ChangePasswordRequest;
import io.github.codejinxz.yilian.auth.dto.LoginRequest;
import io.github.codejinxz.yilian.auth.dto.RegisterRequest;
import io.github.codejinxz.yilian.auth.dto.UserSettingsRequest;
import io.github.codejinxz.yilian.auth.service.AuthService;
import io.github.codejinxz.yilian.auth.service.CaptchaService;
import io.github.codejinxz.yilian.auth.service.JwtTokenService;
import io.github.codejinxz.yilian.auth.vo.AccessToken;
import io.github.codejinxz.yilian.auth.vo.AuthResponse;
import io.github.codejinxz.yilian.auth.vo.CsrfTokenResponse;
import io.github.codejinxz.yilian.auth.vo.CurrentUserResponse;
import io.github.codejinxz.yilian.auth.vo.UserSettingsResponse;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.AuthConstants;
import io.github.codejinxz.yilian.common.security.SecurityUtils;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.common.session.RefreshSessionGuard;
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

    /**
     * 时序对齐用的固定填充口令与对应 BCrypt 假哈希（成本 10，与生产编码器一致）。
     *
     * <p>防的是<strong>登录时序侧信道</strong>（review-verify-2 03#01，P1）：用户名不存在/已停用/已软删时若跳过 BCrypt
     * 直接失败，响应耗时比「存在但密码错」少一次 ~100ms 的 BCrypt 运算，攻击者据此离线枚举有效账号。 三个失败分支统一先对本常量执行一次 {@code matches} 再抛
     * 40101，使存在与否的响应耗时对齐； 哈希是预先算好的固定值， 永不匹配任何真实口令，只承担耗时。
     */
    private static final String TIMING_PADDING_PASSWORD = "yilian-timing-padding-4f8a2c91e7d3b605";

    /** {@link #TIMING_PADDING_PASSWORD} 的 BCrypt 哈希（成本 10）；固定常量保证每次耗时稳定。 */
    @SuppressWarnings("java:S2068") // 不是凭据：密文固定且对应口令无意义，仅用于时序对齐
    private static final String TIMING_PADDING_HASH =
            "$2a$10$eRMlRPfhaLqguHHHIFhyQuKqtNp4ScBstCepsVwf0G20u1qsR.UIq";

    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final CsrfTokenRepository csrfTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final FindByIndexNameSessionRepository<? extends Session> refreshSessions;
    private final CaptchaService captchaService;
    private final RefreshSessionGuard refreshSessionGuard;

    /**
     * 依赖全部面向接口，便于测试时替换账号查询、token 签发与会话吊销。
     *
     * @param userAccountService 账号服务
     * @param passwordEncoder 密码比对器
     * @param csrfTokenRepository CSRF 令牌仓库
     * @param jwtTokenService access token 签发服务
     * @param refreshSessions 按用户名索引的 refresh 会话仓库
     * @param refreshSessionGuard 刷新与吊销的互斥锁与会话纪元护栏
     */
    public AuthServiceImpl(
            UserAccountService userAccountService,
            PasswordEncoder passwordEncoder,
            CsrfTokenRepository csrfTokenRepository,
            JwtTokenService jwtTokenService,
            FindByIndexNameSessionRepository<? extends Session> refreshSessions,
            CaptchaService captchaService,
            RefreshSessionGuard refreshSessionGuard) {
        this.userAccountService = userAccountService;
        this.passwordEncoder = passwordEncoder;
        this.csrfTokenRepository = csrfTokenRepository;
        this.jwtTokenService = jwtTokenService;
        this.refreshSessions = refreshSessions;
        this.captchaService = captchaService;
        this.refreshSessionGuard = refreshSessionGuard;
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
        captchaService.verifyOrThrow(request.captchaId(), request.captchaCode());
        if (PasswordUtils.exceedsBcryptLimit(request.password())) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "密码过长（最多 72 字节）");
        }

        UserCredentials user =
                userAccountService
                        .findByLogin(request.username())
                        .filter(candidate -> candidate.enabled() && !candidate.deleted())
                        .orElse(null);

        if (user == null) {
            // 不存在/停用/软删三个分支统一走假 BCrypt：耗时与「存在但密码错」对齐，账号存在性不可由响应延迟观测。
            passwordEncoder.matches(TIMING_PADDING_PASSWORD, TIMING_PADDING_HASH);
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS);
        }

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
        captchaService.verifyOrThrow(request.captchaId(), request.captchaCode());
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
        // 与吊销（登出/改密/停用）按用户串行化，锁内复检纪元：会话持久化在请求
        // 收尾完成，无纪元时「已吊销仍轮换出新会话」的竞态窗口无法靠删除时序闭合。
        Object indexedName = session.getAttribute(AuthConstants.PRINCIPAL_NAME_ATTRIBUTE);
        if (!(indexedName instanceof String principalName)) {
            invalidateQuietly(session);
            throw new BusinessException(ApiCode.AUTH_REQUIRED);
        }
        return refreshSessionGuard.withLock(
                principalName, () -> refreshLocked(session, httpRequest, principalName));
    }

    /**
     * 刷新的锁内主体：纪元复检 → 属性/绝对期校验 → 账号可用性复检 → 轮换 id → 签发。
     *
     * @param session 当前 refresh 会话
     * @param httpRequest 当前请求
     * @param principalName 会话归属的登录名
     * @return 刷新后的认证响应
     */
    private AuthResponse refreshLocked(
            HttpSession session, HttpServletRequest httpRequest, String principalName) {
        Object epochValue = session.getAttribute(RefreshSessionGuard.EPOCH_ATTRIBUTE);
        // 历史会话无纪元属性按 0 处理：与重启后内存纪元归零的口径一致，不强行全员下线。
        long storedEpoch = epochValue instanceof Long stored ? stored : 0L;
        if (storedEpoch != refreshSessionGuard.epoch(principalName)) {
            // 会话诞生于上一次吊销之前（含并发吊销后滞留轮换的会话），按未登录收敛。
            invalidateQuietly(session);
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
        // 纪元先增（guard.revoke）：并发 in-flight 刷新即便滞留轮换出新会话，下一次刷新也被拒。
        if (username != null) {
            String principalName = username;
            refreshSessionGuard.revoke(principalName, () -> revokeRefreshSessions(principalName));
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
        // 与登出同口径：纪元先增再删除会话，堵住并发刷新在删除后重建会话的窗口。
        refreshSessionGuard.revoke(user.username(), () -> revokeRefreshSessions(user.username()));
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
     * 建立 refresh 会话；写 principal 索引以便按用户批量吊销，并记录当前会话纪元。
     *
     * @param request 当前请求
     * @param user 已认证用户
     */
    private void startSession(HttpServletRequest request, UserCredentials user) {
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthConstants.REFRESH_USER_ID_ATTRIBUTE, user.id());
        session.setAttribute(AuthConstants.REFRESH_CREATED_AT_ATTRIBUTE, DateTimeUtils.now());
        session.setAttribute(AuthConstants.PRINCIPAL_NAME_ATTRIBUTE, user.username());
        session.setAttribute(
                RefreshSessionGuard.EPOCH_ATTRIBUTE, refreshSessionGuard.epoch(user.username()));
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
