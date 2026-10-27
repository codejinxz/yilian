package io.github.codejinxz.yilian.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * 标识符生成工具，统一 UUID 的生成与展示形态。
 *
 * <p>使用 JDK 自带 UUID（版本 4，随机源为 {@link java.security.SecureRandom}）， 不依赖数据库自增或第三方库；本类无状态、线程安全，禁止实例化。
 */
public final class IdUtils {

    private IdUtils() {}

    /**
     * 生成标准带连字符的 UUID，用于需要全局唯一且可直接落库的业务主键。
     *
     * @return 36 位小写 UUID 字符串，形如 {@code 550e8400-e29b-41d4-a716-446655440000}
     */
    public static String newUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * 生成去掉连字符的紧凑 UUID，适合 traceId、缓存键等要求短且不含分隔符的场景。
     *
     * @return 32 位小写十六进制字符串
     */
    public static String newCompactId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Crockford Base32 字母表，ULID 规范固定使用，不含 I/L/O/U，避免人工抄写歧义。 */
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final SecureRandom ULID_RANDOM = new SecureRandom();

    /**
     * 生成 26 位 ULID：前 10 位毫秒时间戳（48 bit），后 16 位随机数（80 bit）。
     *
     * <p>题目 id 需要可按时间排序且不暴露连续自增规律；ULID 同时满足字典序与时间序，写入 DATETIME 之外还便于导入排错。
     *
     * @return 26 位大写 Crockford Base32 字符串
     */
    public static String newUlid() {
        long timestamp = System.currentTimeMillis();
        char[] value = new char[26];
        for (int index = 9; index >= 0; index--) {
            value[index] = CROCKFORD[(int) (timestamp & 0x1f)];
            timestamp >>>= 5;
        }
        byte[] random = new byte[10];
        ULID_RANDOM.nextBytes(random);
        // 80 bit 随机数编码成 16 个 Base32 字符；逐 5 bit 取值，跨字节拼接。
        int buffer = 0;
        int bits = 0;
        int out = 10;
        for (byte current : random) {
            buffer = (buffer << 8) | (current & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                value[out++] = CROCKFORD[(buffer >>> bits) & 0x1f];
            }
        }
        return new String(value);
    }
}
