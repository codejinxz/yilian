package io.github.codejinxz.yilian.common.security;

import org.springframework.session.FindByIndexNameSessionRepository;

import java.time.Duration;

/**
 * auth 与 security 模块共用的稳定契约。
 *
 * <p>这些名字同时出现在请求头解析、refresh 会话属性与 Spring Session 索引中：Bearer 前缀决定 access token 如何从 {@code
 * Authorization} 头取出，refresh 会话属性名与绝对有效期决定会话能否续期， principal 索引名决定管理端能否按用户名吊销会话。集中定义可避免各模块各写一份而逐渐漂移；
 * 修改它们等同于修改对外契约，需要评估已有 refresh 会话的兼容性。
 */
public final class AuthConstants {

    /** {@code Authorization} 请求头中 access token 的前缀，大小写敏感。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** refresh 会话中保存用户 id 的属性名。 */
    public static final String REFRESH_USER_ID_ATTRIBUTE = "yilian.auth.refresh-user-id";

    /** refresh 会话创建时间，用于绝对过期（Spring Session 的 timeout 是滑动过期）。 */
    public static final String REFRESH_CREATED_AT_ATTRIBUTE = "yilian.auth.refresh-created-at";

    /** refresh 绝对有效期，与 cookie max-age / spring.session.timeout 保持一致。 */
    public static final Duration REFRESH_ABSOLUTE_TTL = Duration.ofDays(30);

    /** Spring Session 按 principal 名索引的属性名，用于批量吊销某用户的 refresh 会话。 */
    public static final String PRINCIPAL_NAME_ATTRIBUTE =
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME;

    private AuthConstants() {}
}
