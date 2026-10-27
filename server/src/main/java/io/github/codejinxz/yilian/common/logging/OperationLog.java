package io.github.codejinxz.yilian.common.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要审计的操作接口。
 *
 * <p>只应标在会改变状态或需要追溯的接口上，例如登录、登出、导入、提交答案、管理端修改； 查询类接口默认不标，避免给读路径增加无意义的代理与日志噪音。AOP 只记录方法元数据和
 * 结果摘要，不读取参数、请求体或返回值，密码/token/译文不会因为本注解进入日志。
 *
 * <p>注解属性只能是编译期常量；运行时才能确定的对象 id 由业务方法写入 {@link OperationLogContext}， 切面会在方法结束后用上下文中的非空字段覆盖注解值，并在
 * finally 中清理线程变量。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /**
     * 业务模块名，用于日志和后续审计检索。
     *
     * @return 模块名，例如 auth、content、practice、admin
     */
    String module();

    /**
     * 操作名，使用稳定的英文标识，便于统计和告警。
     *
     * @return 操作名，例如 login、logout、submit-attempt
     */
    String action();

    /**
     * 被操作对象的类型，用于后台按对象追溯，例如 user、sentence、attempt、import-batch。
     *
     * <p>可选；未声明时可由 {@link OperationLogContext} 动态提供，两者都没有时落库为 NULL。 与 {@link #targetId()} 成对使用，
     * 不要把密码、token 或完整译文写进 该字段。
     *
     * @return 对象类型；未声明时为空串
     */
    String targetType() default "";

    /**
     * 被操作对象的稳定标识，例如用户 id、题号或业务单号。
     *
     * <p>可选；未声明时可由 {@link OperationLogContext} 动态提供，两者都没有时落库为 NULL。 注解只能写静态字面量，切面不会读取方法参数或请求体来解析动态
     * id；需要运行时 id 时 由业务方法显式写入上下文。
     *
     * @return 对象标识；未声明时为空串
     */
    String targetId() default "";

    /**
     * 本次操作的审计原因或依据，便于事后解释「为什么做」。
     *
     * <p>可选；未声明时可由 {@link OperationLogContext} 动态提供，两者都没有时落库为 NULL。
     * 该值是注解上的静态说明，不承载管理员在请求中临时填写的原因；动态原因由业务方法写入上下文。 不要把密码、token 或完整译文写进该字段。
     *
     * @return 审计原因；未声明时为空串
     */
    String reason() default "";
}
