/**
 * 通用上传/导入框架（preview→commit 契约，DATA-MODEL §5.6/§5.7）。
 *
 * <p>模块流程：管理员上传文件 → 同步逐行校验（行级结果落 {@code import_batch_rows}，行级失败 只标 INVALID、不改批次状态）→ 管理员确认后单事务
 * commit 写题库 → 可按原因 rollback 软删。 上传到提交为同步链路，不存在跨请求的后台任务与恢复钩子。
 *
 * <p>扩展点：
 *
 * <ol>
 *   <li>业务校验经 {@link io.github.codejinxz.yilian.upload.validation.ImportRowValidator} 组合叠加：
 *       复用默认结构校验后再校验业务字段（指纹去重、chunks 拼接等于 ref 等）；
 *   <li>commit 在单事务内写 {@code sentence_versions} 与 {@code sentences}，失败整批回滚并标 FAILED；
 *   <li>PREVIEWING 超 24 小时置 EXPIRED 的定时清扫未实现，当前仅登记状态枚举。
 * </ol>
 */
package io.github.codejinxz.yilian.upload;
