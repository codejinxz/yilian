package io.github.codejinxz.yilian.auth.vo;

/**
 * 图形验证码挑战（对客户端响应）。
 *
 * @param captchaId 验证码 id，提交时原样带回
 * @param imageBase64 PNG 图片的 base64 文本（不含 data: 前缀），由前端拼 data URL 渲染
 */
public record CaptchaChallenge(String captchaId, String imageBase64) {}
