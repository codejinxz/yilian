package io.github.codejinxz.yilian.common.security;

import io.github.codejinxz.yilian.common.logging.RequestLoggingFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * HTTP 安全边界：无状态 JWT 认证、SPA 可读的 CSRF cookie、按路径划分的公开与鉴权规则、 安全响应头。
 *
 * <p>access token 只从 {@code Authorization: Bearer} 读取，因此不启用表单登录、HTTP Basic、 请求缓存与默认登出端点；未认证/越权由两个
 * handler 输出统一的 {@link io.github.codejinxz.yilian.common.api.ApiResponse} 信封。 刷新会话虽然存放在 Spring
 * Session，但请求本身仍按无状态处理，避免 access token 与会话身份混用。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * CSP 资源来源盘点（依据 web/index.html 与 Vite 构建产物）： 脚本（Vite 打包）、样式（构建 CSS）、图标、manifest、service worker
     * 全部自托管（'self'）； 唯一外部依赖是 Google Fonts——样式表来自 fonts.googleapis.com，字体文件来自 fonts.gstatic.com。
     * React 的内联 style 属性需要 style-src 'unsafe-inline'；Vite 会把小图标内联为 data: URI，故 img-src 带 data:。 PWA
     * 为自托管部署，prod 策略从紧、不含任何第三方脚本来源。
     *
     * <p>包内可见供 {@code SecurityHeadersTest} 整段相等断言：测试直接引用本常量， 策略变更即测试同步失败，避免断言与真实 prod 策略漂移。
     */
    static final String CSP_PROD =
            "default-src 'self'; script-src 'self'; "
                    + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                    + "font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; "
                    + "connect-src 'self'; manifest-src 'self'; worker-src 'self'; "
                    + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    /**
     * local 专用 CSP：springdoc swagger-ui 同源自托管但使用内联脚本与内联样式， 故额外放开 'unsafe-inline'。仅 local profile
     * 生效；local+prod 误叠加时仍取 prod 从紧策略。
     */
    private static final String CSP_LOCAL =
            "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                    + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                    + "font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; "
                    + "connect-src 'self'; manifest-src 'self'; worker-src 'self'; "
                    + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

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
        // CSP 与接口文档同口径：仅 local（且未叠加 prod）放宽给 swagger-ui，其余一律 prod 从紧。
        // Vite dev server(5173) 经代理访问 /api，浏览器不加载后端 HTML，后端 CSP 不影响 HMR，
        // 无需为 dev 额外放开 ws: 来源。
        String cspDirectives = exposeApiDocs ? CSP_LOCAL : CSP_PROD;

        http.csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(csrfRequestHandler))
                // 安全响应头：nosniff / X-Frame-Options:DENY / Referrer-Policy /
                // HSTS / CSP。HSTS 由 Spring Security 仅在 HTTPS 请求上写入（request.isSecure()），
                // local 的 http 自测自然不带；公网部署经反代终结 TLS 时需保证转发头还原 scheme。
                // X-Frame-Options 取 DENY（比 SAMEORIGIN 更紧）：练习页与管理后台均无被嵌入需求，
                // PWA 走浏览器安装而非 iframe，故一概拒绝嵌入；frame-ancestors 'none' 双保险。
                .headers(
                        headers ->
                                headers.contentTypeOptions(Customizer.withDefaults())
                                        .frameOptions(frame -> frame.deny())
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.maxAgeInSeconds(31_536_000L)
                                                                .includeSubDomains(true))
                                        .contentSecurityPolicy(
                                                csp -> csp.policyDirectives(cspDirectives)))
                .authorizeHttpRequests(
                        auth -> {
                            auth.requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                                    .permitAll()
                                    .requestMatchers(
                                            HttpMethod.POST,
                                            "/api/auth/login",
                                            "/api/auth/login/totp",
                                            "/api/auth/refresh",
                                            "/api/auth/register")
                                    .permitAll()
                                    .requestMatchers(HttpMethod.POST, "/api/auth/logout")
                                    .permitAll()
                                    // 图形验证码挑战：公开只读，答案只存服务端，泄露面为图片本身。
                                    .requestMatchers(HttpMethod.GET, "/api/auth/captcha-image")
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
