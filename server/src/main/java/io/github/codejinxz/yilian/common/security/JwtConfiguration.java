package io.github.codejinxz.yilian.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * JWT 基础设施：HS256 密钥、编码器、解码器。
 *
 * <p>密钥长度不足或 prod 使用本地默认值时直接启动失败，避免弱密钥进入部署环境。
 */
@Configuration
public class JwtConfiguration {

    private static final int MIN_SECRET_BYTES = 32;
    private static final String LOCAL_SECRET_PREFIX = "local-dev-only";

    /**
     * 由配置生成 HS256 对称密钥，并在此处集中执行密钥强度校验。
     *
     * @param properties JWT 配置
     * @param environment 用于判断是否 local profile，决定能否使用开发密钥
     * @return HmacSHA256 密钥
     * @throws IllegalStateException 密钥过短、仍是占位符、熵过低或 TTL 超出 10–15 分钟时抛出， 使配置错误在启动阶段暴露而不是运行期
     */
    @Bean
    public SecretKey jwtSecretKey(JwtProperties properties, Environment environment) {
        String secret = properties.secret();
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("yilian.security.jwt.secret 至少需要 32 字节");
        }
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        if (secret.startsWith(LOCAL_SECRET_PREFIX) && !local) {
            throw new IllegalStateException("只有 local profile 可以使用 local-dev-only JWT secret");
        }
        String normalized = secret.toLowerCase();
        if (normalized.contains("change-me")
                || normalized.contains("replace-me")
                || normalized.contains("example")) {
            throw new IllegalStateException("JWT secret 仍是占位符，请生成随机密钥");
        }
        if (secret.chars().distinct().count() < 8) {
            throw new IllegalStateException("JWT secret 熵过低，请使用随机生成的密钥");
        }
        Duration accessTokenTtl = properties.accessTokenTtl();
        if (accessTokenTtl.compareTo(Duration.ofMinutes(10)) < 0
                || accessTokenTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalStateException("access token TTL 必须在 10–15 分钟之间");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * 使用对称密钥签发 HS256 JWT，不引入非对称密钥库。
     *
     * @param jwtSecretKey 已校验的 HS256 密钥
     * @return JWT 编码器
     */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(jwtSecretKey);
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * 解码 access token，并强制校验过期时间与签发者，防止其他系统签发的 token 混入。
     *
     * @param jwtSecretKey 已校验的 HS256 密钥
     * @param properties JWT 配置，提供期望的 issuer
     * @return 只接受本服务签发的 JWT 解码器
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
