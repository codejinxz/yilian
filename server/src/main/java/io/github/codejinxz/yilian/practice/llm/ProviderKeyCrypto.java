package io.github.codejinxz.yilian.practice.llm;

import io.github.codejinxz.yilian.common.security.SecretCipher;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * provider 条目 API Key 的 AES-256-GCM 加解密：加密逻辑与主密钥解析已抽取到跨模块共享的 {@link SecretCipher}（TOTP
 * 密钥落库复用同一加密纪律）， 本类收窄为 provider 域的薄委托—— 保持公共形状零变化（构造器/encrypt/decrypt/last4/异常类型与常量名），027B
 * 的调用点与测试不受影响。
 *
 * <p>主密钥只走环境变量 {@code YILIAN_SECRET_MASTER_KEY}（兜底 Spring 属性 {@code
 * yilian.security.provider-key.master-key}，供 application-local.yml 放文档化 dev 值）， prod
 * 缺失/开发值启动失败、密文格式与 fail-fast 口径见 {@link SecretCipher} 类注释；明文只在内存短暂存在， 本类不打印任何含明文或密钥材料的日志。
 */
@Component
public class ProviderKeyCrypto {

    /** 主密钥的标准环境变量名（部署文档口径；别名转发到共享基建，供既有引用与测试使用）。 */
    public static final String MASTER_KEY_ENV = SecretCipher.MASTER_KEY_ENV;

    /** 兜底 Spring 属性：application-local.yml 放文档化 dev 值用（env 优先于本属性）。 */
    public static final String MASTER_KEY_PROPERTY = SecretCipher.MASTER_KEY_PROPERTY;

    /** 非 prod 缺值时回落到的文档化 dev 默认值；prod 拒绝（local-dev-only 前缀纪律）。 */
    static final String LOCAL_DEV_DEFAULT_KEY = SecretCipher.LOCAL_DEV_DEFAULT_KEY;

    private final SecretCipher cipher;

    /**
     * 解析并校验主密钥；prod 缺值/开发值在构造期直接失败，让配置错误暴露在启动阶段 而不是第一次解密时。
     *
     * @param environment Spring 环境（env var、profile、属性三处读取）
     * @throws IllegalStateException prod 下主密钥缺失、仍是开发值或形态不合法
     */
    public ProviderKeyCrypto(Environment environment) {
        this.cipher = new SecretCipher(environment);
    }

    /**
     * 加密明文密钥：随机 IV 现场生成，AES-256-GCM，输出 Base64(IV ‖ 密文+tag)。
     *
     * @param plaintext 明文 API Key（调用方保证非空白；本方法不写日志）
     * @return 密文字符串（可直接落 llm_provider_keys.secret_cipher 列）
     * @throws ProviderKeyCryptoException 加密失败（JCE 环境异常等，正常不发生）
     */
    public String encrypt(String plaintext) {
        try {
            return cipher.encrypt(plaintext);
        } catch (SecretCipher.SecretCipherException exception) {
            // 共享基建的异常类型归一为本类异常：provider 域调用点与 027B 测试的断言类型不变。
            throw new ProviderKeyCryptoException(exception.getMessage(), exception.getCause());
        }
    }

    /**
     * 解密密文：auth tag 校验通过才返回明文。
     *
     * @param cipherText {@link #encrypt} 产出的密文（Base64(IV ‖ 密文+tag)）
     * @return 明文 API Key
     * @throws ProviderKeyCryptoException 密文格式非法、被篡改或主密钥不符（调用方读路径按 「该条目密钥不存在」处理，回退 env，不阻塞目录读）
     */
    public String decrypt(String cipherText) {
        try {
            return cipher.decrypt(cipherText);
        } catch (SecretCipher.SecretCipherException exception) {
            throw new ProviderKeyCryptoException(exception.getMessage(), exception.getCause());
        }
    }

    /**
     * 明文密钥的尾 4 位辨识号（录入时留痕；少于 4 位取全串）。
     *
     * @param plaintext 明文 API Key
     * @return 尾 4 位（不足 4 位取原串）
     */
    public static String last4(String plaintext) {
        return SecretCipher.last4(plaintext);
    }

    /** 加解密失败异常：读路径按「密钥不存在」吞掉回退，写路径让管理端保存显式失败。 */
    public static class ProviderKeyCryptoException extends RuntimeException {

        /**
         * @param message 中文失败说明（不含明文/密钥材料）
         * @param cause 底层异常（可为 null；AEADBadTagException 等由调用处包装）
         */
        public ProviderKeyCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
