package io.github.codejinxz.yilian.common.security;

import io.github.codejinxz.yilian.common.logging.RequestLoggingFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * HTTP 安全边界：无状态 JWT 认证、SPA 可读的 CSRF cookie、按路径划分的公开与鉴权规则。
 *
 * <p>access token 只从 {@code Authorization: Bearer} 读取，因此不启用表单登录、HTTP Basic、 请求缓存与默认登出端点；未认证/越权由两个
 * handler 输出统一的 {@link io.github.codejinxz.yilian.common.api.ApiResponse} 信封。 刷新会话虽然存放在 Spring
 * Session，但请求本身仍按无状态处理，避免 access token 与会话身份混用。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * BCrypt 密码编码器，强度 10 在启动耗时与暴力破解成本间取平衡。
     *
     * @return 全局共用的密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 适配 SPA 的 CSRF 方案：可读 cookie + 请求头回传。使用明文请求处理器是为了让 cookie 原始值与请求头原始值直接比对，也就是浏览器能从 {@code
     * XSRF-TOKEN} 读到的值。
     *
     * @param environment 用于判断是否 local profile；非 local 一律加 Secure
     * @return 基于 cookie 的 CSRF 令牌仓库
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository(Environment environment) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        boolean secure = !environment.acceptsProfiles(Profiles.of("local"));
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookiePath("/");
        repository.setCookieCustomizer(
                cookie -> cookie.path("/").secure(secure).sameSite("Strict"));
        return repository;
    }

    /**
     * 定义过滤器链顺序与路径权限。
     *
     * <p>只有 CSRF 播种、登录、刷新、登出与健康检查公开；接口文档仅在 local profile（且未 同时激活 prod）放行，其他 profile 即使误开 springdoc
     * 也会落到认证规则；{@code /api/admin/**} 要求 {@code ROLE_ADMIN}，其余接口一律需要认证。登录与刷新虽是公开端点，但仍受 CSRF 校验保护。
     *
     * @param http Spring Security 构建器
     * @param environment 用于判断接口文档是否仅在 local 放行
     * @param csrfTokenRepository CSRF 令牌仓库
     * @param jwtDecoder access token 解码器
     * @param authenticationEntryPoint 未认证时返回 401 信封
     * @param accessDeniedHandler 越权时返回 403 信封
     * @return 构建完成的过滤器链
     * @throws Exception 构建过滤器链失败时由 Spring Security 抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Environment environment,
            CsrfTokenRepository csrfTokenRepository,
            JwtDecoder jwtDecoder,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtDecoder);
        // 接口文档只服务本机联调：显式要求 local 且未激活 prod，local+prod 误叠加时也不放行。
        boolean exposeApiDocs =
                environment.acceptsProfiles(Profiles.of("local"))
                        && !environment.acceptsProfiles(Profiles.of("prod"));

        http.csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(csrfRequestHandler))
                .authorizeHttpRequests(
                        auth -> {
                            auth.requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                                    .permitAll()
                                    .requestMatchers(
                                            HttpMethod.POST,
                                            "/api/auth/login",
                                            "/api/auth/refresh",
                                            "/api/auth/register")
                                    .permitAll()
                                    .requestMatchers(HttpMethod.POST, "/api/auth/logout")
                                    .permitAll()
                                    // 公开题库允许匿名浏览；私有题库在 Service 层按当前用户过滤。
                                    .requestMatchers(HttpMethod.GET, "/api/banks", "/api/banks/**")
                                    .permitAll()
                                    .requestMatchers(
                                            "/actuator/health",
                                            "/actuator/health/**",
                                            "/actuator/info")
                                    .permitAll();
                            // 非 local 不放行 swagger：请求继续落到 /api/admin/** 或 anyRequest() 走认证。
                            if (exposeApiDocs) {
                                auth.requestMatchers(
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll();
                            }
                            auth.requestMatchers("/api/admin/**")
                                    .hasRole("ADMIN")
                                    .anyRequest()
                                    .authenticated();
                        })
                // access token 无状态；refresh token 仍由 Spring Session JDBC 保存，登出可吊销。
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterAfter(jwtAuthenticationFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(new RequestLoggingFilter(), JwtAuthenticationFilter.class);

        return http.build();
    }
}
