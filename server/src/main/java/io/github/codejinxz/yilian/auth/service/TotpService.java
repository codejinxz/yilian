package io.github.codejinxz.yilian.auth.service;

import java.time.Instant;

/**
 * TOTP 核心能力（RFC 6238 自研，JDK 内置 HMAC-SHA1，零新依赖）：密钥生成、Base32 编解码、 动态码校验与 otpauth URI 拼装。
 *
 * <p>口径：HMAC-SHA1、8 位数字、30 秒步长、验证窗口 ±1（当前 ± 前后各一步），比较用 常数时间 {@code MessageDigest.isEqual} 防时序侧信道；密钥
 * 20 字节 SecureRandom 生成、 RFC 4648 Base32 展示。算法选择 SHA1/8 位/30s 是 Google Authenticator 等主流验证器 App
 * 的缺省兼容集，验证器扫码或手动录入即可直接使用。
 */
public interface TotpService {

    /**
     * 生成新的 TOTP 密钥：SecureRandom 20 字节，Base32 编码返回。
     *
     * @return Base32 密钥（32 个字符，字母 A–Z 与数字 2–7）
     */
    String generateSecret();

    /**
     * 校验动态码：按 ±1 窗口对当前时间及前后各一步计算期望码，命中即通过。
     *
     * <p>常数时间比较，避免提前退出泄露「前几位已匹配」的时序信息。
     *
     * @param base32Secret Base32 编码的 TOTP 密钥
     * @param code 用户提交的 8 位动态码（调用方先做格式清洗；非 8 位数字直接返回 false）
     * @param now 校验时刻（统一走注入时钟，测试可控）
     * @return 任一窗口命中为 true；格式不符或全部窗口未命中为 false
     */
    boolean verify(String base32Secret, String code, Instant now);

    /**
     * 拼装 otpauth:// URI 文本：验证器 App 手动录入或未来渲染二维码的素材。
     *
     * @param base32Secret Base32 密钥
     * @param username 展示账户名（URI label 的 {account} 部分）
     * @return 形如 {@code
     *     otpauth://totp/Yilian:{username}?secret=…&issuer=Yilian&algorithm=SHA1&digits=8&period=30}
     *     的 URI
     */
    String otpauthUri(String base32Secret, String username);
}
