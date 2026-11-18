package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端登记 Prompt 新版本请求（API §8.18）。
 *
 * <p>场景名白名单与内容长度由 Bean Validation 前置拦截；版本号不收自客户端——恒为该 name 现存最大 +1（append-only）。
 *
 * @param name 场景名：ask / review / appeal
 * @param content 系统提示全文，非空且 ≤20000 字符（Unicode 码点口径）
 */
public record PromptRegisterRequest(
        @NotBlank(message = "name 不能为空")
                @Pattern(regexp = "ask|review|appeal", message = "name 只能为 ask、review 或 appeal")
                String name,
        @NotBlank(message = "content 不能为空")
                @Size(max = 20000, message = "content 超出长度限制（≤20000 字符）")
                String content) {}
