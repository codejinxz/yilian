package io.github.codejinxz.yilian.common.concurrent;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * 把提交线程的 MDC 与 Spring Security 上下文复制到工作线程，任务结束后再还原工作线程原有上下文。
 *
 * <p>线程池会复用线程，而 {@link MDC} 与 {@link SecurityContextHolder} 默认都是 ThreadLocal； 不做传播时，异步任务的日志会丢
 * {@code traceId}，安全上下文也会拿不到当前用户。传播采用 「提交时快照、执行时覆盖、结束时还原」的顺序，保证同一工作线程连续执行不同任务时不会串上下文。
 *
 * <p>只传播可序列化语义的轻量上下文。不要把密码、token、完整译文放进 MDC，也不要把大对象塞进 SecurityContext；这里复制的是引用，不负责深拷贝。线程池的 {@code
 * TaskDecorator} 与 {@link ContextAwareTaskExecutor} 共用本实现，避免出现两套传播逻辑。
 */
public final class MdcSecurityTaskDecorator implements TaskDecorator {

    /**
     * 在提交线程捕获上下文，返回在工作线程执行前后完成覆盖与还原的包装任务。
     *
     * @param runnable 原始任务，不能为 null
     * @return 带上下文传播的任务；原始任务抛出的异常会原样穿过 finally 继续向上抛
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        Authentication callerAuthentication =
                SecurityContextHolder.getContext().getAuthentication();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            Authentication previousAuthentication =
                    SecurityContextHolder.getContext().getAuthentication();
            try {
                applyMdc(callerMdc);
                applyAuthentication(callerAuthentication);
                runnable.run();
            } finally {
                applyMdc(previousMdc);
                restoreAuthentication(previousAuthentication);
            }
        };
    }

    /**
     * 覆盖当前线程的 MDC；提交线程没有 MDC 时清空，避免继承工作线程上残留的字段。
     *
     * @param context 提交时的 MDC 快照，允许为 null
     */
    private static void applyMdc(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    /**
     * 建立只包含指定认证信息的新安全上下文。
     *
     * @param authentication 提交时的认证信息，未登录时为 null
     */
    private static void applyAuthentication(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        if (authentication != null) {
            context.setAuthentication(authentication);
        }
        SecurityContextHolder.setContext(context);
    }

    /**
     * 还原工作线程原有的安全上下文；原本未登录时清除，避免用户身份泄漏到后续任务。
     *
     * @param previousAuthentication 工作线程执行前的认证信息，允许为 null
     */
    private static void restoreAuthentication(Authentication previousAuthentication) {
        if (previousAuthentication == null) {
            SecurityContextHolder.clearContext();
        } else {
            applyAuthentication(previousAuthentication);
        }
    }
}
