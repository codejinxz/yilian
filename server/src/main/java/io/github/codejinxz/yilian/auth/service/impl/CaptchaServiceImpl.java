package io.github.codejinxz.yilian.auth.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wf.captcha.SpecCaptcha;
import com.wf.captcha.base.Captcha;

import io.github.codejinxz.yilian.auth.service.CaptchaService;
import io.github.codejinxz.yilian.auth.vo.CaptchaChallenge;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.util.StringUtils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * 图形验证码实现：easy-captcha 渲染 + 进程内 Caffeine 存答案。
 *
 * <p>答案只存 id→文本的短命数据（5 分钟、消费即删），不落库不进日志；单实例部署下进程内存储即一致。缓存名按约定为 {@code auth:captcha} （逻辑桶名，非 Redis
 * 键）。
 */
@Service
public class CaptchaServiceImpl implements CaptchaService {

    private static final Duration ANSWER_TTL = Duration.ofMinutes(5);

    private static final int WIDTH = 120;

    private static final int HEIGHT = 40;

    private static final int LENGTH = 4;

    private final Cache<String, String> answers =
            Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(ANSWER_TTL).build();

    private final boolean enabled;

    /**
     * @param enabled 验证码开关；关闭时校验直接放行（本地联调/自动化测试）
     */
    public CaptchaServiceImpl(@Value("${yilian.security.captcha.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public CaptchaChallenge generate() {
        if (!enabled) {
            // 开关关闭：不发挑战，enabled=false 让前端隐藏图形码输入。
            return new CaptchaChallenge("", "", false);
        }
        SpecCaptcha captcha = new SpecCaptcha(WIDTH, HEIGHT, LENGTH);
        captcha.setCharType(Captcha.TYPE_DEFAULT);
        String code = captcha.text();
        String captchaId = UUID.randomUUID().toString();
        answers.put(captchaId, code.toLowerCase(Locale.ROOT));
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            captcha.out(out);
            String imageBase64 = Base64.getEncoder().encodeToString(out.toByteArray());
            return new CaptchaChallenge(captchaId, imageBase64, true);
        } catch (Exception exception) {
            throw new IllegalStateException("验证码图片渲染失败", exception);
        }
    }

    @Override
    public void verifyOrThrow(String captchaId, String captchaCode) {
        if (!enabled) {
            return;
        }
        if (StringUtils.isBlank(captchaId) || StringUtils.isBlank(captchaCode)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "请输入图形验证码");
        }
        // asMap().remove 原子取走并删除：并发同 id 双校验只有一个能拿到答案，堵住消费竞态。
        String expected = answers.asMap().remove(captchaId);
        if (expected == null || !expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "验证码错误或已过期");
        }
    }
}
