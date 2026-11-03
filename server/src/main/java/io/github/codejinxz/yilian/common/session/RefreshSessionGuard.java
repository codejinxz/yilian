package io.github.codejinxz.yilian.common.session;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * refresh 会话吊销护栏（进程内，单实例语义）：按 principal 名维护「会话纪元」与互斥锁。
 *
 * <p>为什么存在（016 P2·03#08）：refresh 与吊销（登出/改密/停用/bootstrap 重置）是并发路径， Spring Session
 * 的会话持久化发生在请求收尾而非服务方法内，「读会话 → 轮换 id」的刷新可以在 吊销删除之后落盘一个新 session id，留下「已全部吊销仍有活会话」的窗口。纪元让这种滞留会话
 * 在下一次刷新即被拒绝；锁把同一用户的刷新与吊销串行化，吊销侧「纪元先增、删除在后」， 刷新侧锁内复检纪元，两个方向都不再依赖持久化时序。
 *
 * <p>进程内状态依赖单实例部署（AGENTS §3：不引入多副本）；重启后纪元归零，历史会话按纪元 0 继续有效——与改造前的行为一致（会话行本就跨重启留存，吊销不追求跨重启记忆）。
 */
@Component
public class RefreshSessionGuard {

    /** 会话内记录纪元用的属性名；定义在本类以避免改 common/security 的 AuthConstants。 */
    public static final String EPOCH_ATTRIBUTE = "yilian.refreshSessionEpoch";

    private final ConcurrentHashMap<String, AtomicLong> epochs = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 读取该用户当前的会话纪元。
     *
     * @param principalName 登录名（Spring Session 的 principal 索引名）
     * @return 纪元值，从未吊销过为 0
     */
    public long epoch(String principalName) {
        AtomicLong counter = epochs.get(principalName);
        return counter == null ? 0L : counter.get();
    }

    /**
     * 在该用户的互斥锁内执行动作；刷新侧在动作内读取 {@link #epoch} 即「锁内复检」。
     *
     * <p>锁按用户名分片，不同用户互不阻塞；同用户内刷新与吊销串行化。
     *
     * @param <T> 动作返回类型
     * @param principalName 登录名
     * @param action 待执行动作
     * @return 动作返回值
     */
    public <T> T withLock(String principalName, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(principalName, key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 执行一次吊销：先递增纪元再在锁内删除会话行。
     *
     * <p>纪元先增保证与并发刷新的任意交错都收敛：刷新若在纪元递增前完成轮换，其会话携带旧 纪元，下一次刷新被拒绝；若在递增后轮换，则本轮锁内复检（刷新侧）或会话行删除直接拦截。
     *
     * @param principalName 登录名
     * @param action 删除会话行的动作（如按 principal 索引逐个 deleteById）
     */
    public void revoke(String principalName, Runnable action) {
        withLock(
                principalName,
                () -> {
                    epochs.computeIfAbsent(principalName, key -> new AtomicLong())
                            .incrementAndGet();
                    action.run();
                    return null;
                });
    }
}
