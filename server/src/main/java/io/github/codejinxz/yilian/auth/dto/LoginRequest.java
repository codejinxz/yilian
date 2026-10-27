package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求体。
 *
 * <p>这里的长度上限只做请求层兜底：密码的 72 字节上限由 {@code AuthService} 按 UTF-8 字节数 再校验一次，因为 BCrypt 只处理前 72
 * 字节，多出的部分会被静默忽略。
 *
 * @param username 登录名，首尾空白不会被自动去除
 * @param password 明文密码，仅在本次请求内使用，不落库也不写日志
 */
@Schema(description = "登录请求体：登录名与明文密码；密码只在本次请求内校验，不落库、不写日志。")
public record LoginRequest(
        @Schema(
                        description = "登录名，区分大小写，首尾空白不会被自动去除",
                        example = "admin",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        maxLength = 64)
                @NotBlank(message = "请输入用户名")
                @Size(max = 64, message = "用户名最多 64 个字符")
                String username,
        @Schema(
                        description = "明文密码；除 72 字符的请求层上限外，服务端还按 UTF-8 字节数限制 72 字节",
                        example = "<your-password>",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.WRITE_ONLY,
                        maxLength = 72)
                @NotBlank(message = "请输入密码")
                @Size(max = 72, message = "密码最多 72 个字符")
                String password) {}
