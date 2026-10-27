package io.github.codejinxz.yilian.auth.service;

import io.github.codejinxz.yilian.auth.dto.AccessToken;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;

/**
 * 签发短期 access token。
 *
 * <p>接口只暴露签发能力，校验由 {@code JwtDecoder} 在过滤器链中完成，两者共用同一份 {@code JwtProperties}，避免签发与校验的 issuer /
 * 算法不一致。
 */
public interface JwtTokenService {

    /**
     * 为已认证用户签发 access token。
     *
     * @param user 认证视图
     * @return 新签发的 token 及有效期
     */
    AccessToken issue(UserCredentials user);
}
