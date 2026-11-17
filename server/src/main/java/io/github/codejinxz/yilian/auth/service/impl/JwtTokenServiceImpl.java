package io.github.codejinxz.yilian.auth.service.impl;

import io.github.codejinxz.yilian.auth.service.JwtTokenService;
import io.github.codejinxz.yilian.auth.vo.AccessToken;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.security.JwtProperties;
import io.github.codejinxz.yilian.common.util.IdUtils;
import io.github.codejinxz.yilian.system.service.dto.UserCredentials;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/** 使用 HS256 签发短期 JWT（access token 与登录 challenge），不保存 token 状态。 */
@Service
public class JwtTokenServiceImpl implements JwtTokenService {

    private final JwtEncoder jwtEncoder;

    private final JwtDecoder jwtDecoder;

    private final JwtProperties properties;

    private final Clock clock;

    /**
     * 注入编码器、解码器与配置；时间统一取 UTC 时钟，避免服务器时区影响 {@code exp}。 challenge 的过期校验复用 access token 同一解码器 （同密钥同
     * issuer，JwtValidators 已含 exp/iss），purpose 差异在解码后按声明区分。
     *
     * @param jwtEncoder 已绑定对称密钥的编码器
     * @param jwtDecoder 已绑定 issuer 与过期校验的解码器（challenge 校验复用）
     * @param properties JWT 配置，提供 issuer 与有效期
     */
    public JwtTokenServiceImpl(
            JwtEncoder jwtEncoder, JwtDecoder jwtDecoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /**
     * 签发 access token。
     *
     * <p>声明只放认证过滤器和审计需要的最小信息：{@code sub} 为用户 id，{@code username} / {@code role} 用于免查库建立上下文，{@code
     * jti} 便于日志串联同一次签发；不含密码、邮箱等敏感数据。不带 purpose——过滤器链据此把它与 challenge 区分开。
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

    /**
     * 签发登录 challenge：密码已验证通过但账号启用 2FA 时使用。声明只带 purpose/username/ jti，不含角色与权限——challenge
     * 不是凭据，拿到它不等于登录成功，过滤器链会拒收带 purpose 的 token。
     *
     * @param userId 待验证用户 id
     * @param username 待验证用户名
     * @return challenge token 字符串
     */
    @Override
    public String issueLoginChallenge(long userId, String username) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(LOGIN_CHALLENGE_TTL);
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .subject(Long.toString(userId))
                        .claim("purpose", LOGIN_CHALLENGE_PURPOSE)
                        .claim("username", username)
                        .id(IdUtils.newUuid())
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * 校验登录 challenge：解析失败、过期、purpose 不符（含 access token 冒充——它没有 purpose） 一律抛 40101 统一口径「请重新登录」，
     * 不区分失败原因以免给探测者反馈。
     *
     * @param token 客户端回传的 challenge token
     * @return 校验通过的用户标识
     */
    @Override
    public LoginChallengeSubject verifyLoginChallenge(String token) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "登录已过期，请重新登录");
        }
        if (!LOGIN_CHALLENGE_PURPOSE.equals(jwt.getClaimAsString("purpose"))) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "登录已过期，请重新登录");
        }
        try {
            return new LoginChallengeSubject(
                    Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("username"));
        } catch (NumberFormatException exception) {
            throw new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "登录已过期，请重新登录");
        }
    }
}
