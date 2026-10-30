package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.Valid;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 游客升级命令（API §4.8）：注册后把本地未同步的提交批量迁入账号。
 *
 * <p>载荷规则与单条提交（§6.2）一致；最多 100 条/次，同 id 同载荷跳过、
 * 异载荷 40903 且整体回滚。迁移只落 attempts/attempt_gradings，不触发 FSRS 排程
 * （本地历史是记录不是队列；复习卡由服务端后续 FIRST 自然生成）。
 *
 * @param attempts 本地攒批的提交，最多 100 条
 */
public record GuestUpgradeCommand(
        @Valid @Size(max = 100, message = "单次最多迁移 100 条") List<AttemptCommand> attempts) {}
