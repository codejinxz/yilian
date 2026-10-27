/**
 * 通用上传/导入框架。
 *
 * <p>当前切片只做「上传 → 批次 → 异步逐行结构校验 → 进度/失败摘要 → 断点续跑」， 刻意不写任何题库业务表。后续接 {@code question_versions/chunks}
 * 时：
 *
 * <ol>
 *   <li>新增一个 {@link io.github.codejinxz.yilian.upload.validation.ImportRowValidator} 实现，
 *       复用默认结构校验后再校验业务字段与 {@code chunks} 拼接等于 {@code ref}；
 *   <li>仅当批次没有失败行时，在短事务内批量写业务表；写入失败整批回滚并标记 FAILED；
 *   <li>行级错误持久化到独立明细表，不要把原文塞进 {@code import_batch.error_message}。
 * </ol>
 */
package io.github.codejinxz.yilian.upload;
