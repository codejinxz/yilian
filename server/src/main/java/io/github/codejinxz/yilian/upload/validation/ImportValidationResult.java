package io.github.codejinxz.yilian.upload.validation;

import io.github.codejinxz.yilian.common.util.StringUtils;

/**
 * 单行校验结果。
 *
 * <p>失败原因会进入 errors 接口的样例列表，必须是可给管理员看的中文短句， 不能包含整行原文、SQL、堆栈或密钥。
 *
 * @param valid 是否通过校验
 * @param reason 失败原因；通过时为 null
 */
public record ImportValidationResult(boolean valid, String reason) {

    /** 归一化失败原因，保证失败结果始终有可展示的文案。 */
    public ImportValidationResult {
        if (valid) {
            reason = null;
        } else if (StringUtils.isBlank(reason)) {
            reason = "校验未通过";
        }
    }

    /**
     * 通过校验。
     *
     * @return 成功结果
     */
    public static ImportValidationResult ok() {
        return new ImportValidationResult(true, null);
    }

    /**
     * 校验失败。
     *
     * @param reason 面向管理员的中文原因，允许为 null（会兜底为「校验未通过」）
     * @return 失败结果
     */
    public static ImportValidationResult invalid(String reason) {
        return new ImportValidationResult(false, reason);
    }
}
