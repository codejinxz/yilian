package io.github.codejinxz.yilian.auth.service.impl;

import io.github.codejinxz.yilian.auth.dto.AccessToken;
import io.github.codejinxz.yilian.auth.service.JwtTokenService;
import io.github.codejinxz.yilian.common.security.JwtProperties;
import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/** 使用 HS256 签发短期 JWT，不保存 access token 状态。 */
@Service
public class JwtTokenServiceImpl implements JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    /**
     * 注入编码器与配置；时间统一取 UTC 时钟，避免服务器时区影响 {@code exp}。
     *
     * @param jwtEncoder 已绑定对称密钥的编码器
     * @param properties JWT 配置，提供 issuer 与有效期
     */
    public JwtTokenServiceImpl(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /**
     * 签发 access token。
     *
     * <p>声明只放认证过滤器和审计需要的最小信息：{@code sub} 为用户 id，{@code username} / {@code role} 用于免查库建立上下文，{@code
     * jti} 便于日志串联同一次签发；不含密码、邮箱等敏感数据。
     *
     * @param user 认证视图
     * @return 新签发的 token 及有效期
     */
    @Override
    public AccessToken issue(UserCredentials user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .subject(Long.toString(user.id()))
                        .claim("username", user.username())
                        .claim("role", user.role().name())
                        .claim("pwdChanged", user.passwordChanged())
                        .id(IdUtils.newUuid())
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, "Bearer", properties.accessTokenTtl().toSeconds());
    }
}
