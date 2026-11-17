package io.github.codejinxz.yilian.practice.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.practice.dao.PromptVersionDao;
import io.github.codejinxz.yilian.practice.entity.PromptVersion;
import io.github.codejinxz.yilian.practice.entity.PromptVersion.PromptLabel;
import io.github.codejinxz.yilian.practice.llm.PromptRegistry;
import io.github.codejinxz.yilian.practice.service.PromptAdminService;
import io.github.codejinxz.yilian.practice.service.vo.PromptVersionView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 管理端 Prompt 版本治理实现（API §8.18）：检索走只读查询，登记与激活走定向 UPDATE 面 + 注册表缓存失效。
 *
 * <p>append-only 纪律的落地点：登记只 INSERT（版本号服务端分配）；激活只经 {@link PromptVersionDao#updateLabel} 的定向
 * UPDATE（SET 面只有 label 一列，行内容三列在 SQL 面不可达）；激活的降级/升级/缓存失效在同一 {@code @Transactional}
 * 内——任何一步失败整体回滚，不会出现「新旧行标签互换半截」或「缓存已失效而库未提交」的中间态 （缓存失效即使先于提交可见，后果只是缓存空了回源读库，方向安全）。
 */
@Service
public class PromptAdminServiceImpl implements PromptAdminService {

    private static final Logger log = LoggerFactory.getLogger(PromptAdminServiceImpl.class);

    /** 每页条数缺省值（011 分页口径同款）。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限；前端可传更大值，越界归 100 而不是报错。 */
    static final int MAX_PAGE_SIZE = 100;

    /** 页码上限：offset = (page-1)*size 需落在 int 内，同时拦住无意义的深翻页（011 口径）。 */
    private static final int MAX_PAGE = 10_000;

    /** 场景名白名单：与 PromptRegistry 取用键同源（ask 答疑 / review 模型复核 / appeal 申诉初裁）。 */
    private static final Set<String> NAME_WHITELIST = Set.of("ask", "review", "appeal");

    /** 标签过滤合法取值。 */
    private static final Set<String> LABEL_FILTERS = Set.of("ACTIVE", "CANDIDATE");

    /** 登记内容长度上限（Unicode 码点口径，TEXT 列 64KB 内的量级）。 */
    static final int CONTENT_MAX_CHARS = 20_000;

    private final PromptVersionDao promptVersionDao;

    private final PromptRegistry promptRegistry;

    /**
     * @param promptVersionDao Prompt 版本 Dao（登记/检索/label 流转）
     * @param promptRegistry Prompt 版本注册表（激活后失效缓存，即时换轨）
     */
    public PromptAdminServiceImpl(
            PromptVersionDao promptVersionDao, PromptRegistry promptRegistry) {
        this.promptVersionDao = promptVersionDao;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public PageResponse<PromptVersionView> list(String name, String label, int page, int size) {
        String nameFilter = normalizeName(name);
        String labelFilter = normalizeFilter(label, LABEL_FILTERS, "label");
        if (page < 1 || page > MAX_PAGE || size < 1) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "分页参数不合法");
        }
        int pageSize = Math.min(size, MAX_PAGE_SIZE);
        int offset = Math.multiplyExact(pageSize, page - 1);
        PromptLabel labelEnum = labelFilter == null ? null : PromptLabel.valueOf(labelFilter);
        List<PromptVersionView> items =
                promptVersionDao.search(nameFilter, labelEnum, offset, pageSize).stream()
                        .map(PromptAdminServiceImpl::toView)
                        .toList();
        long total = promptVersionDao.countSearch(nameFilter, labelEnum);
        return PageResponse.of(new ArrayList<>(items), page, pageSize, total);
    }

    @Override
    @Transactional
    public PromptVersionView register(String name, String content, Long operatorId) {
        String sceneName = normalizeName(name);
        if (content == null || content.isBlank()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "content 不能为空");
        }
        if (content.codePointCount(0, content.length()) > CONTENT_MAX_CHARS) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "content 超出长度限制（≤20000 字符）");
        }
        // 版本号服务端分配：现存最大 +1（append-only；并发登记撞 uk_prompt_name_version 时按
        // 40900 收敛，重试即取到下一个版本号——幂等以数据库唯一约束为准，应用层只做友好返回）。
        int nextVersion = promptVersionDao.findMaxVersion(sceneName).orElse(0) + 1;
        PromptVersion created =
                PromptVersion.create(
                        sceneName, nextVersion, content, PromptLabel.CANDIDATE, Instant.now());
        try {
            created = promptVersionDao.save(created);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "同名场景并发登记冲突，请重试（版本号已分配给先到者）");
        }
        log.info(
                "prompt_registered operatorId={} name={} version={} id={}",
                operatorId,
                sceneName,
                nextVersion,
                created.getId());
        return toView(created);
    }

    @Override
    @Transactional
    public PromptVersionView activate(long id, Long operatorId) {
        PromptVersion target =
                promptVersionDao
                        .findById(id)
                        .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND));
        if (target.getLabel() == PromptLabel.ACTIVE) {
            // 幂等口径：对当前 ACTIVE 行重复激活（双击/重试）直接返回现值，不重复降级也不改写历史。
            log.info("prompt_activated idempotent operatorId={} id={}", operatorId, id);
            return toView(target);
        }
        // 激活事务三步：旧 ACTIVE 降 CANDIDATE → 新行置 ACTIVE → 注册表缓存失效（下一次读取即时换轨）。
        // 旧 ACTIVE 按取用口径至多一行，但注册表读端对多 ACTIVE 有「取最高版本」容忍，降级全部旧 ACTIVE
        // 行保证「同 name 至多一个 ACTIVE」的应用层约定成立。
        for (PromptVersion active :
                promptVersionDao.search(target.getName(), PromptLabel.ACTIVE, 0, MAX_PAGE_SIZE)) {
            if (active.getId() != target.getId()) {
                promptVersionDao.updateLabel(active.getId(), PromptLabel.CANDIDATE);
            }
        }
        int updated = promptVersionDao.updateLabel(target.getId(), PromptLabel.ACTIVE);
        if (updated == 0) {
            // 行在上面的 findById 之后被并发删除（现无 DELETE 端点，防御性兜底）按 40400 收敛。
            throw new BusinessException(ApiCode.NOT_FOUND);
        }
        // 写后失效：三条 LLM 链路（ask/review/appeal）下一次 load 即取新版本（028 指纹换轨语义不破坏）。
        promptRegistry.evictAll();
        log.info(
                "prompt_activated operatorId={} name={} version={} id={}",
                operatorId,
                target.getName(),
                target.getVersion(),
                target.getId());
        return toView(promptVersionDao.findById(target.getId()).orElse(target));
    }

    /**
     * 场景名归一：空白视为未过滤；非法取值按 40000 拒绝（与申诉检索筛选同口径，静默归全会让 管理员误以为筛选生效）。
     *
     * @param value 原始参数，可空
     * @return 归一后的场景名；不过滤为 null
     */
    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (!NAME_WHITELIST.contains(normalized)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "name 只能为 ask、review 或 appeal");
        }
        return normalized;
    }

    /**
     * 过滤参数归一：空白视为不过滤；非法取值按 40000 拒绝。
     *
     * @param value 原始参数，可空
     * @param allowed 合法取值
     * @param field 字段名（错误文案用）
     * @return 归一后的过滤值；不过滤为 null
     */
    private static String normalizeFilter(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, field + " 取值不合法");
        }
        return normalized;
    }

    /**
     * 实体 → 视图。
     *
     * @param version 版本行
     * @return 视图
     */
    private static PromptVersionView toView(PromptVersion version) {
        return new PromptVersionView(
                version.getId(),
                version.getName(),
                version.getVersion(),
                version.getLabel().name(),
                version.getContent(),
                version.getCreatedAt());
    }
}
