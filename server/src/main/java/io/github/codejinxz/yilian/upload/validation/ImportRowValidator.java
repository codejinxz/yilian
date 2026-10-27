package io.github.codejinxz.yilian.upload.validation;

/**
 * 导入行校验扩展点。
 *
 * <p>框架按 Spring Bean 顺序组合所有实现：任一校验器返回失败即计为失败行，后续校验器不再执行。 默认实现 {@link DefaultImportRowValidator}
 * 只做通用结构校验（空行、超长、JSON 行语法）； 接入题库时新增一个校验器即可，不需要改异步处理与进度逻辑。
 *
 * <p><strong>后续接 question_versions/chunks 的扩展点</strong>：
 *
 * <ol>
 *   <li>新增 {@code QuestionImportRowValidator}，在默认校验之后解析 {@code zh/ref/chunks/blank/note}；
 *   <li>按 PRD §6 校验 {@code zh} 长度、{@code chunks} 非空、{@code blank} 下标、{@code note} 长度；
 *   <li>把 {@code chunks[].answers[0]} 按序拼接并与归一化后的 {@code ref} 比较，不一致直接判该行失败；
 *   <li>只有整批没有失败行时，才在同一个事务内写 {@code question_versions} 与 {@code chunks}， 失败则整批回滚并把批次标记
 *       FAILED；当前框架刻意不写任何业务表。
 * </ol>
 *
 * <p>实现必须线程安全：同一批次在单线程中顺序执行，但不同批次可能并发调用。
 */
public interface ImportRowValidator {

    /**
     * 校验一行数据。
     *
     * @param row 行号、格式与原始行文本
     * @return 通过或带中文原因的失败结果；不得返回 null
     */
    ImportValidationResult validate(ImportRow row);
}
