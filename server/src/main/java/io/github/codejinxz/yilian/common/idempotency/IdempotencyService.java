package io.github.codejinxz.yilian.common.idempotency;

/**
 * 幂等占位能力。
 *
 * <p>只提供通用能力，不绑定具体业务表：调用方把 attemptId、邀请码等稳定业务键交给 {@link #tryAcquire}，由数据库唯一约束决定胜负。
 * 唯一约束包含归属者、作用域与键，因此不同用户或不同业务作用域之间不会互相占位。
 */
public interface IdempotencyService {

    /**
     * 尝试占用一个幂等键。
     *
     * @param request 幂等请求，归属者、作用域与键共同决定是否重复
     * @return true 表示本次首次占用；false 表示同一归属者、作用域下键已存在
     * @throws org.springframework.dao.DataAccessException 数据库不可用时向上抛出，按失败关闭处理
     */
    boolean tryAcquire(IdempotencyRequest request);

    /**
     * 业务执行失败时释放占位，允许客户端用同一个键重试。
     *
     * <p>只删除与请求归属者、作用域、键及请求摘要都匹配的记录，不会影响其他用户的同键占位。
     *
     * @param request 原幂等请求
     */
    void release(IdempotencyRequest request);
}
