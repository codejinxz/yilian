package io.github.codejinxz.yilian.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 接线（AGENTS §3.1 双持久层契约）：MyBatis 只承载复杂查询/统计/报表的只读 Dao， 写操作与单表 CRUD 仍走 Spring Data JPA。
 *
 * <p>Mapper 接口统一放在各模块 {@code dao.mybatis} 包，由本扫描注册；业务代码只依赖模块 {@code dao} 包下的 {@code *Dao}
 * 契约接口，不直接注入 Mapper。SQL 统一写在 resources 的 mapper 目录 XML 中（位置见 application.yml 的
 * mapper-locations），动态筛选与 JOIN 不散落在注解里。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan("io.github.codejinxz.yilian.**.dao.mybatis")
public class MyBatisConfig {
    // 扫描约定即全部配置；无额外 Bean，保持 mybatis-spring-boot 自动装配主导。
}
