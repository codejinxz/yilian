package io.github.codejinxz.yilian.content.service.dto;

/**
 * 题库视图，对应 API §5.1 / §5.2。
 *
 * @param id 题库 id，字符串化
 * @param ownerId 所有者 id；官方题库为 null
 * @param name 名称
 * @param description 说明
 * @param visibility 可见性
 * @param status 生命周期
 * @param version 银行级修订号
 * @param sentenceCount 已发布且未删题目数
 */
public record BankView(
        String id,
        String ownerId,
        String name,
        String description,
        String visibility,
        String status,
        long version,
        long sentenceCount) {

    /**
     * 构造视图。
     *
     * @param id 题库 id
     * @param ownerId 所有者 id
     * @param name 名称
     * @param description 说明
     * @param visibility 可见性
     * @param status 状态
     * @param version 版本
     * @param sentenceCount 题目数
     * @return 视图
     */
    public static BankView of(
            long id,
            Long ownerId,
            String name,
            String description,
            String visibility,
            String status,
            long version,
            long sentenceCount) {
        return new BankView(
                Long.toString(id),
                ownerId == null ? null : Long.toString(ownerId),
                name,
                description,
                visibility,
                status,
                version,
                sentenceCount);
    }
}
