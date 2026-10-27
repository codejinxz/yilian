package io.github.codejinxz.yilian.common.logging;

import java.util.Optional;

/**
 * 单次操作的审计字段上下文，供业务代码补充注解无法表达的动态值。
 *
 * <p>{@link OperationLog} 的属性只能写编译期常量，而导入批次等场景的对象 id 要到方法执行后 才能确定。业务方法在拿到动态值后调用 {@link #set(String,
 * String, String)} 写入当前线程； {@link OperationLogAspect} 在方法结束后读取并覆盖注解中的同名静态值，然后统一清理。
 *
 * <p><strong>清理责任在切面</strong>：业务代码只负责写，不负责清。切面在进入标注方法前清空一次， 在 finally 中读取后再清空一次，确保 Tomcat
 * 线程复用时不会把上一个请求的批次 id 带到下一个请求。 本类不提供跨线程传播，异步任务需要审计时应显式传递值。
 */
public final class OperationLogContext {

    /** 当前线程的审计覆盖值；没有设置过任何字段时为 null。 */
    private static final ThreadLocal<AuditOverride> CURRENT = new ThreadLocal<>();

    private OperationLogContext() {}

    /**
     * 写入本次操作的审计字段，覆盖注解中的同名静态值。
     *
     * <p>允许个别字段为 null 或空白，此时该字段回退到注解值；三个字段都是空白等价于未设置。 后一次调用会整体替换前一次调用，不做字段级合并，避免多次设置后语义含糊。
     *
     * @param targetType 被操作对象类型，例如 import-batch；可为 null
     * @param targetId 被操作对象标识，例如批次 id；可为 null
     * @param reason 本次操作的审计原因；可为 null
     */
    public static void set(String targetType, String targetId, String reason) {
        CURRENT.set(new AuditOverride(targetType, targetId, reason));
    }

    /**
     * 读取当前线程的审计覆盖值，供切面解析落库参数。
     *
     * @return 本次操作已写入的覆盖值；未写入时为 {@link Optional#empty()}
     */
    public static Optional<AuditOverride> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 清空当前线程的审计覆盖值。
     *
     * <p>只应由 {@link OperationLogAspect} 在操作结束时调用；业务代码不手动清理， 否则切面在 finally 中就读不到动态字段。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 一次操作写入的审计覆盖值，字段允许为 null，由切面按字段回退到注解值。
     *
     * @param targetType 被操作对象类型；可为 null
     * @param targetId 被操作对象标识；可为 null
     * @param reason 审计原因；可为 null
     */
    public record AuditOverride(String targetType, String targetId, String reason) {}
}
