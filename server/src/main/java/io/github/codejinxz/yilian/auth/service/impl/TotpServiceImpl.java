package io.github.codejinxz.yilian.auth.service.impl;

import io.github.codejinxz.yilian.auth.service.TotpService;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP 核心实现（RFC 6238 / RFC 4648，JDK 自带能力，零新依赖）。
 *
 * <p>实现要点：
 *
 * <ul>
 *   <li><strong>算法</strong>：HMAC-SHA1（RFC 6238 推荐 SHA1，与 Google Authenticator 缺省一致）+ 动态截断 （RFC 4226
 *       §5.3：取末字节低 4 位为偏移，读 4 字节去符号位 mod 10^digits）。
 *   <li><strong>窗口</strong>：验证时按 counter-1 / counter / counter+1 三个值各算一次期望码，任一命中即通过—— 容忍验证器与服务器之间
 *       ±30 秒的时钟漂移，是 2FA 的通行口径；超出窗口的码（含历史与超未来）一律拒绝。
 *   <li><strong>常数时间</strong>：候选码与提交码的比较统一走 {@link MessageDigest#isEqual}，且三个窗口都算完才返回，
 *       不因命中位置提前退出；防时序侧信道。
 *   <li><strong>Base32</strong>：手写 RFC 4648 编解码（字母 A–Z、数字 2–7，不含 0/1 易混字符），只支持标准字母表、 不接受 padding
 *       以外的宽松输入；密钥 20 字节 → 32 个 Base32 字符。
 * </ul>
 */
@Service
public class TotpServiceImpl implements TotpService {

    /** RFC 6238 步长：30 秒。 */
    static final int STEP_SECONDS = 30;

    /** 验证窗口：当前步 ±1（前后各容忍一步时钟漂移）。 */
    static final int WINDOW_STEPS = 1;

    /** 动态码位数：8 位。 */
    static final int CODE_DIGITS = 8;

    /** 10^8，动态截断后的取模基数。 */
    private static final int CODE_MODULUS = 100_000_000;

    /** 密钥字节长度：20 字节（RFC 4226 建议 ≥16，20 字节是通行口径）。 */
    static final int SECRET_BYTES = 20;

    /** RFC 4648 Base32 字母表。 */
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** Base32 每字符承载的比特数。 */
    private static final int BASE32_BITS_PER_CHAR = 5;

    private static final char[] BASE32_CHARS = BASE32_ALPHABET.toCharArray();

    /** 256 字节到 Base32 字符的查找表：index = 字节值，value = 对应字符（-1 表示非法）。 */
    private static final byte[] BASE32_LOOKUP = buildBase32Lookup();

    private static final String HMAC_SHA1 = "HmacSHA1";

    private final SecureRandom secureRandom = new SecureRandom();

    /** 构建字节值 → Base32 字符序号的查找表。 */
    private static byte[] buildBase32Lookup() {
        byte[] lookup = new byte[256];
        Arrays.fill(lookup, (byte) -1);
        for (int i = 0; i < BASE32_CHARS.length; i++) {
            lookup[BASE32_CHARS[i]] = (byte) i;
        }
        return lookup;
    }

    @Override
    public String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return base32Encode(secret);
    }

    @Override
    public boolean verify(String base32Secret, String code, Instant now) {
        if (code == null
                || code.length() != CODE_DIGITS
                || !code.chars().allMatch(Character::isDigit)) {
            // 格式不符直接拒绝：不进入 HMAC 计算，也不给「格式差异」的时序信号。
            return false;
        }
        byte[] secret = base32Decode(base32Secret);
        if (secret == null) {
            return false;
        }
        long currentStep = Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
        // 常数时间纪律：三个窗口都算完并完成比较才汇总结论，不因命中提前返回。
        boolean matched = false;
        for (int offset = -WINDOW_STEPS; offset <= WINDOW_STEPS; offset++) {
            String expected = hotp(secret, currentStep + offset);
            if (MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                matched = true;
            }
        }
        return matched;
    }

    @Override
    public String otpauthUri(String base32Secret, String username) {
        // label 与 issuer 的口径与主流验证器一致：label=issuer:account，issuer 重复出现在 query 里，
        // 验证器据此在条目列表里展示「Yilian (username)」。
        return "otpauth://totp/Yilian:"
                + username
                + "?secret="
                + base32Secret
                + "&issuer=Yilian&algorithm=SHA1&digits="
                + CODE_DIGITS
                + "&period="
                + STEP_SECONDS;
    }

    /**
     * RFC 4226 HOTP：HMAC-SHA1 → 动态截断 → 8 位十进制。
     *
     * @param secret 密钥字节
     * @param counter 步进计数
     * @return 8 位动态码（前导零保留）
     */
    private static String hotp(byte[] secret, long counter) {
        byte[] message = new byte[8];
        for (int shift = 7; shift >= 0; shift--) {
            message[7 - shift] = (byte) (counter >>> (shift * 8));
        }
        Mac mac;
        try {
            mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(secret, HMAC_SHA1));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            // JDK 必带 HmacSHA1，初始化失败属 JVM 环境异常而非业务分支。
            throw new IllegalStateException("HMAC-SHA1 不可用（JDK 环境异常）", exception);
        }
        byte[] digest = mac.doFinal(message);
        // 动态截断：末字节低 4 位为偏移，取 4 字节并去掉最高符号位（RFC 4226 §5.3）。
        int offset = digest[digest.length - 1] & 0x0F;
        int binary =
                ((digest[offset] & 0x7F) << 24)
                        | ((digest[offset + 1] & 0xFF) << 16)
                        | ((digest[offset + 2] & 0xFF) << 8)
                        | (digest[offset + 3] & 0xFF);
        return String.format("%0" + CODE_DIGITS + "d", binary % CODE_MODULUS);
    }

    /**
     * RFC 4648 Base32 编码：不带 padding（验证器录入口径）。
     *
     * @param data 原始字节
     * @return Base32 字符串
     */
    static String base32Encode(byte[] data) {
        StringBuilder output =
                new StringBuilder(
                        (data.length * 8 + BASE32_BITS_PER_CHAR - 1) / BASE32_BITS_PER_CHAR);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= BASE32_BITS_PER_CHAR) {
                output.append(BASE32_CHARS[(buffer >>> (bitsLeft - BASE32_BITS_PER_CHAR)) & 0x1F]);
                bitsLeft -= BASE32_BITS_PER_CHAR;
            }
        }
        if (bitsLeft > 0) {
            output.append(BASE32_CHARS[(buffer << (BASE32_BITS_PER_CHAR - bitsLeft)) & 0x1F]);
        }
        return output.toString();
    }

    /**
     * RFC 4648 Base32 解码：只接受标准字母表，非法字符返回 null（视为密钥格式错误而非抛异常—— 解密失败与校验失败同归「verify 不通过」路径）。
     *
     * @param encoded Base32 字符串
     * @return 解码字节；格式非法时为 null
     */
    static byte[] base32Decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        int bits = 0;
        int buffer = 0;
        byte[] output = new byte[encoded.length() * BASE32_BITS_PER_CHAR / 8];
        int outputLength = 0;
        for (int i = 0; i < encoded.length(); i++) {
            char character = encoded.charAt(i);
            if (character == '=') {
                // padding 只允许出现在末尾，出现后不得再有有效字符。
                for (int rest = i + 1; rest < encoded.length(); rest++) {
                    if (encoded.charAt(rest) != '=') {
                        return null;
                    }
                }
                break;
            }
            int value = character < 256 ? BASE32_LOOKUP[character] : -1;
            if (value < 0) {
                return null;
            }
            buffer = (buffer << BASE32_BITS_PER_CHAR) | value;
            bits += BASE32_BITS_PER_CHAR;
            if (bits >= 8) {
                output[outputLength++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        return Arrays.copyOf(output, outputLength);
    }
}
