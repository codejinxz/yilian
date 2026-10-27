package io.github.codejinxz.yilian.system.service;

/**
 * 启动时初始化 bootstrap 管理员。
 *
 * <p>独立成服务是为了让 {@code BootstrapAdminInitializer} 只保留 ApplicationRunner 的壳， 校验/创建/修复逻辑可脱离 Spring
 * 启动流程单独测试。
 */
public interface BootstrapAdminService {

    /**
     * 幂等执行；prod 下凭据缺失或仍为默认值时抛 {@link IllegalStateException} 阻止启动。
     *
     * @throws IllegalStateException 用户名格式非法，或 prod 密码缺失、过短、为默认值/弱口令时抛出
     */
    void initialize();
}
