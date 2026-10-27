package io.github.codejinxz.yilian.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT access token 配置。
 *
 * @param issuer 签发者，写入 iss 并校验
 * @param secret HS256 密钥，至少 32 字节；local 可用开发默认值，prod 必须来自环境变量
 * @param accessTokenTtl access token 有效期，建议 15 分钟以内
 */
@ConfigurationProperties(prefix = "yilian.security.jwt")
public record JwtProperties(String issuer, String secret, Duration accessTokenTtl) {

    /** 为空时回落到安全默认值，使本地启动无需额外配置；合法性（长度、占位符、TTL 区间） 留给 {@link JwtConfiguration} 在创建密钥时统一校验。 */
    public JwtProperties {
        issuer = issuer == null || issuer.isBlank() ? "yilian" : issuer;
        secret = secret == null ? "" : secret;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
    }
}
