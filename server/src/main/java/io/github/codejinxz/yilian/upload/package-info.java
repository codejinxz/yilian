/**
 * 通用上传/导入框架（preview→commit 契约，DATA-MODEL §5.6/§5.7）。
 *
 * <p>当前切片：管理员上传文件 → 同步逐行校验（行级结果落 {@code import_batch_rows}，行级失败 只标 INVALID、不改批次状态）→ 管理员确认后单事务
 * commit 写题库 → 可按原因 rollback 软删。 旧「上传 → 异步 PROCESSING → resume」过渡机制已删除，不存在跨请求的后台任务与恢复钩子。
 *
 * <p>后续扩展方向：
 *
 * <ol>
 *   <li>新增 {@link io.github.codejinxz.yilian.upload.validation.ImportRowValidator} 实现，
 *       复用默认结构校验后再校验业务字段（指纹去重、chunks 拼接等于 ref 等）；
 *   <li>commit 在单事务内写 {@code sentence_versions} 与 {@code sentences}，失败整批回滚并标 FAILED；
 *   <li>PREVIEWING 超 24 小时的批次由定时任务置 EXPIRED（本期只留状态枚举，任务后置）。
 * </ol>
 */
package io.github.codejinxz.yilian.upload;
