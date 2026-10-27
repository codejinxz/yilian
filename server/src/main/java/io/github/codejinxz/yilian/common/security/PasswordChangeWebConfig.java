package io.github.codejinxz.yilian.common.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册强制改密拦截器。 */
@Configuration
public class PasswordChangeWebConfig implements WebMvcConfigurer {

    private final PasswordChangeRequiredInterceptor interceptor;

    /**
     * 注入拦截器。
     *
     * @param interceptor 强制改密拦截器
     */
    public PasswordChangeWebConfig(PasswordChangeRequiredInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    /**
     * 对所有请求注册；拦截器内部只处理 {@code /api/**}。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/**");
    }
}
