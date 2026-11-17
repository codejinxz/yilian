package io.github.codejinxz.yilian.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link TotpServiceImpl} 单测 [AC-1]：RFC 6238 附录 B 全部 6 个 SHA1 测试向量对拍、 ±1 窗口边界（过期/未来一步接受、两步外拒绝）、
 * Base32（RFC 4648）编解码往返与 otpauth URI 形状。
 *
 * <p>算法正确性由 RFC 固定向量保证；窗口与格式分支用「测试内参考实现」（同原语、独立写的 截断逻辑）对拍，避免用被测代码自己验证自己。
 */
class TotpServiceImplTest {

    /** RFC 6238 附录 B 规定的测试密钥：ASCII "12345678901234567890"（20 字节）。 */
    private static final byte[] RFC_SECRET_BYTES =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** 上述密钥的 RFC 4648 Base32 编码（公开常量，交叉验证编码器）。 */
    private static final String RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private final TotpServiceImpl totp = new TotpServiceImpl();

    /** [AC-1] RFC 6238 附录 B：全部 6 个 SHA1 向量逐一对拍（T 秒 → 8 位码）。 */
    @Test
    @DisplayName("[AC-1] RFC 6238 附录 B 全部 6 个 SHA1 向量")
    void ac1_rfc6238AppendixBAllSixVectors() {
        assertThat(RFC_SECRET_BYTES).hasSize(20);
        // 编码器先对拍 RFC 规定的 Base32 密钥串，再逐向量验证动态码。
        assertThat(TotpServiceImpl.base32Encode(RFC_SECRET_BYTES)).isEqualTo(RFC_SECRET_BASE32);
        assertThat(TotpServiceImpl.base32Decode(RFC_SECRET_BASE32)).isEqualTo(RFC_SECRET_BYTES);

        record Vector(long time, String code) {}
        Vector[] vectors = {
            new Vector(59L, "94287082"),
            new Vector(1111111109L, "07081804"),
            new Vector(1111111111L, "14050471"),
            new Vector(1234567890L, "89005924"),
            new Vector(2000000000L, "69279037"),
            new Vector(20000000000L, "65353130"),
        };
        for (Vector vector : vectors) {
            assertThat(
                            totp.verify(
                                    RFC_SECRET_BASE32,
                                    vector.code(),
                                    Instant.ofEpochSecond(vector.time())))
                    .as("RFC 6238 附录 B T=%d 应命中 %s", vector.time(), vector.code())
                    .isTrue();
        }
    }

    /** [AC-1] 窗口边界 +1：提交下一步的码（相邻向量 T=1111111111 的码在 T=1111111109 验证）应接受。 */
    @Test
    @DisplayName("[AC-1] 未来一步的码在窗口内接受")
    void ac1_nextWindowAccepted() {
        assertThat(totp.verify(RFC_SECRET_BASE32, "14050471", Instant.ofEpochSecond(1111111109L)))
                .isTrue();
    }

    /** [AC-1] 窗口边界 -1：提交上一步的码（相邻向量 T=1111111109 的码在 T=1111111111 验证）应接受。 */
    @Test
    @DisplayName("[AC-1] 过期一步的码在窗口内接受")
    void ac1_previousWindowAccepted() {
        assertThat(totp.verify(RFC_SECRET_BASE32, "07081804", Instant.ofEpochSecond(1111111111L)))
                .isTrue();
    }

    /** [AC-1] 超窗拒绝：两步前的码与两步后的码在当前时刻都拒绝（参考实现对拍，非被测代码自证）。 */
    @Test
    @DisplayName("[AC-1] 窗口外（±2 步）拒绝")
    void ac1_beyondWindowRejected() {
        // 参考实现：独立写的 HOTP（HMAC-SHA1 + RFC 4226 §5.3 截断），用于计算窗口外码。
        long currentStep = 1111111111L / 30;
        String twoStepsBefore = referenceHotp(RFC_SECRET_BYTES, currentStep - 2);
        String twoStepsAfter = referenceHotp(RFC_SECRET_BYTES, currentStep + 2);
        assertThat(twoStepsBefore).isNotEqualTo("14050471");
        assertThat(
                        totp.verify(
                                RFC_SECRET_BASE32,
                                twoStepsBefore,
                                Instant.ofEpochSecond(1111111111L)))
                .isFalse();
        assertThat(
                        totp.verify(
                                RFC_SECRET_BASE32,
                                twoStepsAfter,
                                Instant.ofEpochSecond(1111111111L)))
                .isFalse();
    }

    /** [AC-1] 错码与格式不符拒绝：错码、7 位、字母混入、空串、null 一律 false，不抛异常。 */
    @Test
    @DisplayName("[AC-1] 格式不符与错码拒绝")
    void ac1_wrongOrMalformedCodeRejected() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        assertThat(totp.verify(RFC_SECRET_BASE32, "00000000", now)).isFalse();
        assertThat(totp.verify(RFC_SECRET_BASE32, "1405047", now)).isFalse();
        assertThat(totp.verify(RFC_SECRET_BASE32, "1405047a", now)).isFalse();
        assertThat(totp.verify(RFC_SECRET_BASE32, "", now)).isFalse();
        assertThat(totp.verify(RFC_SECRET_BASE32, null, now)).isFalse();
        // 密钥格式非法（含非 Base32 字符）同样归一为 false，不抛异常。
        assertThat(totp.verify("not!base32!!", "14050471", now)).isFalse();
    }

    /** [AC-1] Base32 往返：随机字节编码后解码还原；非法字符拒绝；padding 容忍。 */
    @Test
    @DisplayName("[AC-1] Base32 编解码往返与非法输入")
    void ac1_base32RoundTripAndRejects() {
        SecureRandom random = new SecureRandom();
        for (int round = 0; round < 32; round++) {
            byte[] raw = new byte[1 + random.nextInt(64)];
            random.nextBytes(raw);
            String encoded = TotpServiceImpl.base32Encode(raw);
            assertThat(encoded).matches("[A-Z2-7]+");
            assertThat(TotpServiceImpl.base32Decode(encoded)).isEqualTo(raw);
        }
        // 非法字符（0/1 不在字母表）、小写、空串：解码返回 null（verify 归一为不通过）。
        assertThat(TotpServiceImpl.base32Decode("0189")).isNull();
        assertThat(TotpServiceImpl.base32Decode("gezdgnbv")).isNull();
        assertThat(TotpServiceImpl.base32Decode("")).isNull();
        assertThat(TotpServiceImpl.base32Decode(null)).isNull();
        // 标准 padding 形态容忍（RFC 4648 canonical form）。
        assertThat(TotpServiceImpl.base32Decode(RFC_SECRET_BASE32 + "===="))
                .isEqualTo(RFC_SECRET_BYTES);
    }

    /** [AC-1] 密钥生成：32 个 Base32 字符、可解码为 20 字节、两次生成互不相同。 */
    @Test
    @DisplayName("[AC-1] 密钥生成形状与随机性")
    void ac1_generatedSecretShape() {
        String first = totp.generateSecret();
        String second = totp.generateSecret();
        assertThat(first).matches("[A-Z2-7]{32}");
        assertThat(second).matches("[A-Z2-7]{32}");
        assertThat(first).isNotEqualTo(second);
        assertThat(TotpServiceImpl.base32Decode(first)).hasSize(20);
    }

    /** [AC-1] otpauth URI 形状：label=issuer:account、secret/issuer/algorithm/digits/period 五参数齐备。 */
    @Test
    @DisplayName("[AC-1] otpauth URI 形状锁定")
    void ac1_otpauthUriShape() {
        String uri = totp.otpauthUri(RFC_SECRET_BASE32, "admin");
        assertThat(uri)
                .isEqualTo(
                        "otpauth://totp/Yilian:admin?secret="
                                + RFC_SECRET_BASE32
                                + "&issuer=Yilian&algorithm=SHA1&digits=8&period=30");
        // 生成密钥可原样进 URI（端到端素材自洽）。
        String generated = totp.otpauthUri(totp.generateSecret(), "learner");
        assertThat(generated)
                .startsWith("otpauth://totp/Yilian:learner?secret=")
                .endsWith("&issuer=Yilian&algorithm=SHA1&digits=8&period=30")
                .matches("otpauth://totp/Yilian:learner\\?secret=[A-Z2-7]{32}.*");
    }

    /**
     * 测试内参考 HOTP 实现（RFC 4226 §5.3）：只用于窗口边界对拍，算法正确性由附录 B 向量保证。
     *
     * @param secret 密钥字节
     * @param counter 步进计数
     * @return 8 位动态码
     */
    private static String referenceHotp(byte[] secret, long counter) throws IllegalStateException {
        byte[] message = new byte[8];
        for (int i = 0; i < 8; i++) {
            message[i] = (byte) (counter >>> (8 * (7 - i)));
        }
        Mac mac;
        try {
            mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        } catch (Exception exception) {
            throw new IllegalStateException("参考实现 HMAC 初始化失败", exception);
        }
        byte[] digest = mac.doFinal(message);
        int offset = digest[digest.length - 1] & 0x0F;
        int binary =
                ((digest[offset] & 0x7F) << 24)
                        | ((digest[offset + 1] & 0xFF) << 16)
                        | ((digest[offset + 2] & 0xFF) << 8)
                        | (digest[offset + 3] & 0xFF);
        return String.format("%08d", binary % 100_000_000);
    }
}
