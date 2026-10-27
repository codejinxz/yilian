/**
 * 异步执行基础设施：执行器、上下文传播、超时降级与结构化并发替代方案。
 *
 * <p>本包只解决「任务怎么跑、上下文怎么传、超时怎么办」，不承载业务逻辑。所有执行器都在 {@link
 * io.github.codejinxz.yilian.common.concurrent.AsyncConfig} 中集中定义，业务代码只按名称注入， 不自行创建线程池，也禁止使用裸
 * {@code new Thread}。
 */
package io.github.codejinxz.yilian.common.concurrent;
