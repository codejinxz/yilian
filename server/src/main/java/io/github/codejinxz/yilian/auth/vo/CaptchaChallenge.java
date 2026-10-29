package io.github.codejinxz.yilian.auth.vo;

/**
 * 图形验证码挑战（对客户端响应）。
 *
 * @param captchaId 验证码 id，提交时原样带回
 * @param imageBase64 PNG 图片的 base64 文本（不含 data: 前缀），由前端拼 data URL 渲染
 * @param enabled 验证码开关状态；关闭时前端据此隐藏图形码输入
 */
public record CaptchaChallenge(String captchaId, String imageBase64, boolean enabled) {}
