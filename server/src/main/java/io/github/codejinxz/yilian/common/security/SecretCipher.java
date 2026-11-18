package io.github.codejinxz.yilian.common.security;

import io.github.codejinxz.yilian.common.config.DeploymentMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 跨模块共享的 AES-256-GCM 对称加密基建：落库敏感凭据（provider API Key、TOTP 密钥等） 统一经本类加密，主密钥<strong>只走环境变量</strong>
 * {@code YILIAN_SECRET_MASTER_KEY}（兜底 Spring 属性 {@code yilian.security.provider-key.master-key}，供
 * application-local.yml 放文档化 dev 值）——主密钥本身永不入库、不进日志。
 *
 * <p><strong>主密钥取值口径（与 {@code JwtConfiguration} 的 JWT secret 纪律对齐）</strong>： 读现有 JWT secret
 * 处理风格后选定如下一致方案——
 *
 * <ul>
 *   <li>JWT secret 是「原始口令，UTF-8 字节 ≥32，prod 拒绝 {@code local-dev-only} 前缀」； 本主密钥保持同样的 prod fail-fast
 *       与 local-dev-only 纪律，另支持两种显式编码形态 （base64/hex）以便部署方表达高熵 32 字节密钥；<strong>不引入新的口令派生依赖</strong>，
 *       原始口令形态用 JDK 自带 SHA-256 单次派生为 32 字节 AES-256 密钥。
 *   <li>解析顺序（确定性，同一字符串恒解析出同一密钥字节）：{@code ^[0-9a-fA-F]{64}$} 按 hex 解码为 32 字节；否则合法 base64 且解码恰为 32
 *       字节按 base64；否则按原始 UTF-8 口令 （≥32 字节）SHA-256 派生。歧义由长度约束收窄：恰为 64 hex 字符或恰好 base64 出 32 字节
 *       的原始口令视为编码形态（文档化口径，部署方用原始口令时避开这两种长度即可）。
 * </ul>
 *
 * <p><strong>fail-fast 口径（复用 prod 环境判定 {@link DeploymentMode#isProd}）</strong>： prod（profile 含 prod
 * 或 {@code YILIAN_PROD_MARKER=true}）下主密钥缺失或仍是 {@code local-dev-only} 开发值 → 构造期抛异常启动失败；非
 * prod（local/test）缺值时回落到类内文档化 dev 默认值（与 application-local.yml 的 local-dev-only 值同文），仅限本机开发，禁止复用。
 *
 * <p><strong>兜底属性名沿用 {@code yilian.security.provider-key.master-key}</strong>：本类由 provider
 * 密钥加密器抽取而来（TOTP 密钥入库复用同一加密纪律），application-local.yml 与 application-test.yml 已按该属性名 配置文档化 dev
 * 值；改名会让既有部署的已加密数据无法解密，故属性名保持原样，语义由「provider 密钥主密钥」 扩展为「本类所有落库凭据的主密钥」。
 *
 * <p><strong>密文格式</strong>：Base64(12 字节随机 IV ‖ GCM 密文含 128 位 auth tag)。IV 每次加密 用 {@link
 * SecureRandom} 现场生成（GCM 语义要求 IV 绝不复用）；解密时 auth tag 校验失败 （密文被篡改/主密钥不符）抛 {@link
 * SecretCipherException}，读路径调用方按「密钥不存在」 处理，不放大成调用失败。明文只在方法参数/返回值的内存里短暂存在，本类不打任何 含明文的日志。
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    /** 主密钥的标准环境变量名（部署文档口径；prod 只从这里取）。 */
    public static final String MASTER_KEY_ENV = "YILIAN_SECRET_MASTER_KEY";

    /** 兜底 Spring 属性：application-local.yml 放文档化 dev 值用（env 优先于本属性；名称沿用 provider-key 原属性名以保配置兼容）。 */
    public static final String MASTER_KEY_PROPERTY = "yilian.security.provider-key.master-key";

    /** 非 prod 缺值时回落到的文档化 dev 默认值；prod 拒绝（local-dev-only 前缀纪律）。 */
    public static final String LOCAL_DEV_DEFAULT_KEY =
            "local-dev-only-yilian-master-key-0123456789abcdef";

    /** local-dev-only 前缀：prod 拒绝以它开头的取值（与 JWT secret 同纪律）。 */
    private static final String LOCAL_DEV_PREFIX = "local-dev-only";

    /** AES-256 密钥字节长度。 */
    private static final int KEY_BYTES = 32;

    /** GCM IV 字节长度（NIST 推荐 96 bit）。 */
    private static final int IV_BYTES = 12;

    /** GCM auth tag 位长（128 bit）。 */
    private static final int TAG_BITS = 128;

    /** GCM 算法名（JDK 自带，零新依赖）。 */
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec masterKey;

    /** IV 随机源；实例级复用 SecureRandom（线程安全），只产 IV 不产密钥。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 解析并校验主密钥；prod 缺值/开发值在构造期直接失败，让配置错误暴露在启动阶段 而不是第一次解密时。
     *
     * @param environment Spring 环境（env var、profile、属性三处读取）
     * @throws IllegalStateException prod 下主密钥缺失、仍是开发值或形态不合法
     */
    public SecretCipher(Environment environment) {
        String raw = environment.getProperty(MASTER_KEY_ENV);
        if (raw == null || raw.isBlank()) {
            raw = environment.getProperty(MASTER_KEY_PROPERTY);
        }
        boolean prod = DeploymentMode.isProd(environment);
        if (raw == null || raw.isBlank()) {
            if (prod) {
                throw new IllegalStateException(
                        "prod 环境必须设置 YILIAN_SECRET_MASTER_KEY（32 字节主密钥，用于 provider 条目"
                                + " API Key 等落库凭据加密），缺失即启动失败");
            }
            log.warn(
                    "未设置 YILIAN_SECRET_MASTER_KEY，回落到文档化 local dev 默认主密钥"
                            + "（仅限本机开发，禁止复用到共享/生产环境）");
            raw = LOCAL_DEV_DEFAULT_KEY;
        }
        if (raw.startsWith(LOCAL_DEV_PREFIX) && prod) {
            throw new IllegalStateException("prod 环境拒绝 local-dev-only 主密钥，请换成环境变量中的随机 32 字节密钥");
        }
        this.masterKey = parseMasterKey(raw);
    }

    /**
     * 加密明文：随机 IV 现场生成，AES-256-GCM，输出 Base64(IV ‖ 密文+tag)。
     *
     * @param plaintext 明文（调用方保证非空白；本方法不写日志）
     * @return 密文字符串（可直接落库密文列）
     * @throws SecretCipherException 加密失败（JCE 环境异常等，正常不发生）
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception exception) {
            // 不携带明文与密钥信息：异常消息只暴露失败阶段，细节看错误日志里的堆栈。
            throw new SecretCipherException("密钥加密失败", exception);
        }
    }

    /**
     * 解密密文：auth tag 校验通过才返回明文。
     *
     * @param cipherText {@link #encrypt} 产出的密文（Base64(IV ‖ 密文+tag)）
     * @return 明文
     * @throws SecretCipherException 密文格式非法、被篡改或主密钥不符（调用方读路径按 「密钥不存在」处理，不阻塞主链路）
     */
    public String decrypt(String cipherText) {
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText);
            if (payload.length <= IV_BYTES) {
                throw new SecretCipherException("密文长度非法（不足 IV + tag）", null);
            }
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec);
            byte[] decrypted = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (SecretCipherException exception) {
            throw exception;
        } catch (Exception exception) {
            // AEADBadTagException（篡改/主密钥不符）等一律归一为本异常：调用方无需区分失败原因。
            throw new SecretCipherException("密钥解密失败（密文篡改或主密钥不符）", exception);
        }
    }

    /**
     * 明文的尾 4 位辨识号（录入时留痕；少于 4 位取全串）。
     *
     * @param plaintext 明文
     * @return 尾 4 位（不足 4 位取原串）
     */
    public static String last4(String plaintext) {
        if (plaintext.length() <= 4) {
            return plaintext;
        }
        return plaintext.substring(plaintext.length() - 4);
    }

    /**
     * 主密钥解析：hex（64 位十六进制）→ base64（解码恰 32 字节）→ 原始口令（≥32 字节， SHA-256 派生）；解析顺序与歧义口径见类注释，prod
     * 下非法形态启动失败。
     *
     * @param raw 主密钥原始字符串
     * @return AES-256 密钥
     * @throws IllegalStateException 形态不合法（长度不足等）
     */
    private static SecretKeySpec parseMasterKey(String raw) {
        // 1) hex：恰好 64 个十六进制字符 → 直接 32 字节（部署方用 openssl rand -hex 32 的产物）。
        if (raw.matches("[0-9a-fA-F]{64}")) {
            return new SecretKeySpec(hexDecode(raw), "AES");
        }
        // 2) base64：合法且解码恰为 32 字节 → 直接用（部署方用 openssl rand -base64 32 的产物）。
        if (raw.matches("[A-Za-z0-9+/]{42,44}={0,2}")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(raw);
                if (decoded.length == KEY_BYTES) {
                    return new SecretKeySpec(decoded, "AES");
                }
            } catch (IllegalArgumentException ignored) {
                // 非法 base64 落到原始口令分支，不在此报错。
            }
        }
        // 3) 原始口令：UTF-8 字节 ≥32，SHA-256 单次派生为 32 字节（与 JWT secret 的「原始口令
        //    直用字节」风格一致，仅此处多一步定长派生以满足 AES-256 的密钥长度）。
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < KEY_BYTES) {
            throw new IllegalStateException(
                    "YILIAN_SECRET_MASTER_KEY 至少需要 32 字节（hex 64 字符 / base64 编码 32 字节 /"
                            + " 原始口令 ≥32 字符）");
        }
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(bytes);
            return new SecretKeySpec(Arrays.copyOf(derived, KEY_BYTES), "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("主密钥派生失败（JDK 环境异常）", exception);
        }
    }

    /**
     * 十六进制解码（入口已由正则保证形态）。
     *
     * @param hex 64 位十六进制字符串
     * @return 32 字节
     */
    private static byte[] hexDecode(String hex) {
        byte[] out = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 加解密失败异常：读路径按「密钥不存在」吞掉回退，写路径让管理端保存显式失败。 */
    public static class SecretCipherException extends RuntimeException {

        /**
         * @param message 中文失败说明（不含明文/密钥材料）
         * @param cause 底层异常（可为 null；AEADBadTagException 等由调用处包装）
         */
        public SecretCipherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
