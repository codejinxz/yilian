package io.github.codejinxz.yilian.system.bootstrap;

import io.github.codejinxz.yilian.system.service.BootstrapAdminService;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动钩子：只负责在容器就绪后触发一次 bootstrap 管理员初始化，逻辑都在服务实现里。 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final BootstrapAdminService bootstrapAdminService;

    /**
     * 只依赖服务接口，钩子本身不承载校验与持久化逻辑。
     *
     * @param bootstrapAdminService bootstrap 管理员初始化服务
     */
    public BootstrapAdminInitializer(BootstrapAdminService bootstrapAdminService) {
        this.bootstrapAdminService = bootstrapAdminService;
    }

    /**
     * 容器启动完成后执行一次初始化；配置不合法时异常会中断启动，避免带病上线。
     *
     * @param args 启动参数，本钩子不使用
     */
    @Override
    public void run(ApplicationArguments args) {
        bootstrapAdminService.initialize();
    }
}
