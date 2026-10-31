package io.github.codejinxz.yilian.admin.service.vo;

import io.github.codejinxz.yilian.system.service.vo.InviteCodeAdminView;

import java.util.List;

/**
 * 管理端邀请码列表响应体：非分页，固定形状 {@code {items: [...]}}。
 *
 * @param items 邀请码列表项，创建时间倒序、最多 50 条
 */
public record AdminInviteListView(List<InviteCodeAdminView> items) {}
