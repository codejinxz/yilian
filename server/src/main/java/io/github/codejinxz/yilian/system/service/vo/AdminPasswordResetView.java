package io.github.codejinxz.yilian.system.service.vo;

/**
 * 管理端重置密码回执（API §8.2b）。
 *
 * <p>临时口令只在本次响应返回一次，库里只落 BCrypt；前端负责提示管理员转交并强调一次性。
 *
 * @param temporaryPassword 一次性临时口令明文（与邀请码同风格的去混淆字母表 SecureRandom）
 * @param userId 目标用户 id（字符串化，避免前端精度丢失）
 * @param username 目标用户登录名
 */
public record AdminPasswordResetView(String temporaryPassword, String userId, String username) {}
