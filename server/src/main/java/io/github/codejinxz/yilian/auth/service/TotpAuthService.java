package io.github.codejinxz.yilian.auth.service;

import io.github.codejinxz.yilian.auth.dto.TotpActivateRequest;
import io.github.codejinxz.yilian.auth.dto.TotpDisableRequest;
import io.github.codejinxz.yilian.auth.dto.TotpSetupRequest;
import io.github.codejinxz.yilian.auth.vo.TotpActivationView;
import io.github.codejinxz.yilian.auth.vo.TotpSetupView;
import io.github.codejinxz.yilian.auth.vo.TotpStatusView;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;

/**
 * 两步验证自助用例（登录态，/api/auth/2fa/**）：状态查询、注册两步（setup → activate）、 关闭（双确认）。
 *
 * <p>机制对所有登录用户开放（公网硬前置面是管理员，机制本身不设角色门槛）；编排依赖 auth 模块内的 TOTP 算法与加密基建，持久化经 system 模块 {@code
 * TotpAccountService} 完成。
 */
public interface TotpAuthService {

    /**
     * 读取当前用户两步验证状态。
     *
     * @param principal 当前用户
     * @return 状态视图（enabled/enrolledAt/剩余恢复码数），不含任何密钥材料
     */
    TotpStatusView status(YilianUserPrincipal principal);

    /**
     * 注册第一步：密码确认后生成新密钥——密文落库、Base32 密钥与 otpauth URI 仅本次展示； 重复调用作废旧密钥，启用开关不变。
     *
     * @param principal 当前用户
     * @param request 密码确认
     * @return Base32 密钥与 otpauth URI 文本
     */
    TotpSetupView setup(YilianUserPrincipal principal, TotpSetupRequest request);

    /**
     * 注册第二步：验证器动态码通过后启用，生成 10 个一次性恢复码——明文仅本次返回， 库内只存 BCrypt 哈希。
     *
     * @param principal 当前用户
     * @param request 验证器当前动态码
     * @return 一次性恢复码明文列表
     */
    TotpActivationView activate(YilianUserPrincipal principal, TotpActivateRequest request);

    /**
     * 关闭两步验证：密码 + 当前动态码双确认后清空注册与全部恢复码。
     *
     * @param principal 当前用户
     * @param request 密码与动态码
     */
    void disable(YilianUserPrincipal principal, TotpDisableRequest request);
}
