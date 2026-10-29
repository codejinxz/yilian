package io.github.codejinxz.yilian.auth.service;

import io.github.codejinxz.yilian.auth.vo.CaptchaChallenge;

/**
 * 图形验证码服务：生成挑战与一次性校验。
 *
 * <p>答案存进程内 Caffeine（单实例部署约定，AGENTS §3）；5 分钟过期、消费即作废， 防止同一挑战被反复试码。开关 {@code
 * yilian.security.captcha.enabled} 关闭时 {@link #verifyOrThrow} 直接放行，前端也不展示图形码。
 */
public interface CaptchaService {

    /**
     * 生成图形验证码挑战。
     *
     * @return 验证码 id 与 base64 图片
     */
    CaptchaChallenge generate();

    /**
     * 校验并消费验证码；无论对错都立即作废该挑战，防同 id 反复试码。
     *
     * @param captchaId 验证码 id
     * @param captchaCode 用户输入的验证码
     * @throws io.github.codejinxz.yilian.common.api.BusinessException 开关开启且缺失/错误/过期时抛 40000
     */
    void verifyOrThrow(String captchaId, String captchaCode);
}
