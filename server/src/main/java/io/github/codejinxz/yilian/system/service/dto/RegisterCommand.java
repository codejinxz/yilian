package io.github.codejinxz.yilian.system.service.dto;

/**
 * 注册命令；由 auth 模块组装，system 模块负责唯一性、邀请码与落库。
 *
 * @param email 邮箱，必填
 * @param username 登录名；为空时由邮箱本地部分派生
 * @param password 明文密码，只在服务内部编码，不写日志
 * @param inviteCode 邀请码明文；关闭邀请制时可为 null
 */
public record RegisterCommand(String email, String username, String password, String inviteCode) {}
