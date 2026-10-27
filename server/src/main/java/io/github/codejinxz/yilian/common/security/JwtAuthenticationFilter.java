package io.github.codejinxz.yilian.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 Authorization: Bearer 解析 access token 并建立本次请求的安全上下文。
 *
 * <p>刷新 token 不在这里处理：refresh 端点使用 HttpOnly Cookie 和 Spring Session。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtDecoder jwtDecoder;

    /**
     * 由安全配置注入解码器，过滤器本身不持有密钥。
     *
     * @param jwtDecoder 已绑定 issuer 校验的 JWT 解码器
     */
    public JwtAuthenticationFilter(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * 仅在请求携带 Bearer token 时尝试认证，随后无条件放行；是否拒绝由授权规则决定。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 后续过滤器抛出时传递
     * @throws IOException 读写请求/响应失败时传递
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(AuthConstants.BEARER_PREFIX)) {
            authenticate(request, authorization.substring(AuthConstants.BEARER_PREFIX.length()));
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 校验 token 并把 principal 写入上下文；失败时保持匿名，交给入口点统一返回 401。
     *
     * @param request 当前请求，保留用于后续按需读取其他请求信息
     * @param token 去掉 Bearer 前缀后的 JWT 字符串
     */
    private void authenticate(HttpServletRequest request, String token) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String subject = jwt.getSubject();
            String username = jwt.getClaimAsString("username");
            String role = jwt.getClaimAsString("role");
            Boolean passwordChanged = jwt.getClaim("pwdChanged");
            if (subject == null || subject.isBlank() || username == null || role == null) {
                throw new JwtException("JWT 缺少必要声明");
            }
            YilianUserPrincipal principal =
                    YilianUserPrincipal.of(
                            Long.parseLong(subject),
                            username,
                            role,
                            true,
                            passwordChanged == null || passwordChanged);
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            principal, token, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException exception) {
            // 无效 token 按未认证处理，由入口点统一返回 401；不回显 token 或异常细节。
            SecurityContextHolder.clearContext();
        }
    }
}
