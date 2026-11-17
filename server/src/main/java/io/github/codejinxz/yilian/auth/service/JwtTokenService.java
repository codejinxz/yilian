package io.github.codejinxz.yilian.auth.service;

import io.github.codejinxz.yilian.auth.vo.AccessToken;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;

/**
 * 签发短期 access token 与登录第二步 challenge token。
 *
 * <p>接口只暴露签发与 challenge 校验能力，access token 的校验由 {@code JwtDecoder} 在过滤器链中完成，两者共用同一份 {@code
 * JwtProperties}，避免签发与校验的 issuer / 算法不一致。
 */
public interface JwtTokenService {

    /** challenge JWT 的 purpose 声明值；verify 端点只认该 purpose，主 access token 不携带 purpose。 */
    String LOGIN_CHALLENGE_PURPOSE = "login-totp";

    /** challenge 有效期：5 分钟——覆盖验证器时钟漂移下的重试余量，同时把泄露窗口收敛到分钟级。 */
    java.time.Duration LOGIN_CHALLENGE_TTL = java.time.Duration.ofMinutes(5);

    /**
     * 为已认证用户签发 access token。
     *
     * @param user 认证视图
     * @return 新签发的 token 及有效期
     */
    AccessToken issue(UserCredentials user);

    /**
     * 为「密码已验证通过、等待两步验证」的登录签发 challenge token：5 分钟 TTL、purpose=login-totp。
     *
     * @param userId 待验证用户 id
     * @param username 待验证用户名（写进声明便于日志串联与第二步上下文）
     * @return challenge token 字符串（不是 access token，过滤器链拒收）
     */
    String issueLoginChallenge(long userId, String username);

    /**
     * 校验登录 challenge token：签名/过期/issuer 走统一解码器，purpose 必须恰为 login-totp。
     *
     * @param token 客户端回传的 challenge token
     * @return 校验通过的用户标识
     * @throws io.github.codejinxz.yilian.common.api.BusinessException token 无效、过期或 purpose
     *     不符（统一收敛，不区分失败原因）
     */
    LoginChallengeSubject verifyLoginChallenge(String token);

    /** challenge 校验通过后的用户标识。 */
    record LoginChallengeSubject(long userId, String username) {}
}
