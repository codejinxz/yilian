package io.github.codejinxz.yilian.practice.service.impl;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.practice.llm.LlmProtocol;
import io.github.codejinxz.yilian.practice.llm.LlmProviderCatalog;
import io.github.codejinxz.yilian.practice.llm.LlmProviderDescriptor;
import io.github.codejinxz.yilian.practice.service.LlmProviderAdminService;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderCreateRequest;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmProviderView;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link LlmProviderAdminService} 实现：目录读写经 {@link LlmProviderCatalog}（app_settings 单键，末写胜出）， hasKey
 * 经 env 索引。并发写目录是低频管理操作，读-改-写不加速锁（冲突窗口极小且最坏只是丢一次元数据修改， 无数据完整性风险）；激活切换 /
 * 删除保护等服务端规则全部在本层强制（前端隐藏不算权限）。
 */
@Service
public class LlmProviderAdminServiceImpl implements LlmProviderAdminService {

    private final LlmProviderCatalog catalog;

    /**
     * 注入依赖。
     *
     * @param catalog provider 目录存取
     */
    public LlmProviderAdminServiceImpl(LlmProviderCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<LlmProviderView> list() {
        LlmProviderCatalog.Catalog snapshot = catalog.read();
        return snapshot.providers().stream()
                .map(descriptor -> toView(descriptor, snapshot.activeId()))
                .toList();
    }

    @Override
    public LlmProviderView create(LlmProviderCreateRequest request, Long operatorId) {
        LlmProtocol protocol = requireProtocol(request.protocol());
        LlmProviderDescriptor descriptor =
                new LlmProviderDescriptor(
                        request.id(), request.name(), protocol, request.baseUrl(), request.model());
        // Bean Validation 已约束 id 形态，这里兜底校验（服务端唯一防线，防未来调用方绕过注解）。
        if (!descriptor.hasValidId()) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "id 只允许小写字母、数字与连字符，且最长 64 字符");
        }
        LlmProviderCatalog.Catalog snapshot = catalog.read();
        if (snapshot.providers().stream()
                .anyMatch(existing -> existing.id().equals(descriptor.id()))) {
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED, "provider id 已存在：" + descriptor.id());
        }
        List<LlmProviderDescriptor> next = new ArrayList<>(snapshot.providers());
        next.add(descriptor);
        catalog.saveProviders(next, operatorId);
        return toView(descriptor, snapshot.activeId());
    }

    @Override
    public LlmProviderView update(String id, LlmProviderUpdateRequest request, Long operatorId) {
        LlmProtocol protocol = requireProtocol(request.protocol());
        LlmProviderDescriptor updated =
                new LlmProviderDescriptor(
                        id, request.name(), protocol, request.baseUrl(), request.model());
        LlmProviderCatalog.Catalog snapshot = catalog.read();
        List<LlmProviderDescriptor> next = new ArrayList<>();
        boolean found = false;
        for (LlmProviderDescriptor existing : snapshot.providers()) {
            if (existing.id().equals(updated.id())) {
                next.add(updated);
                found = true;
            } else {
                next.add(existing);
            }
        }
        if (!found) {
            throw new BusinessException(ApiCode.NOT_FOUND, "provider 不存在：" + updated.id());
        }
        catalog.saveProviders(next, operatorId);
        return toView(updated, snapshot.activeId());
    }

    @Override
    public void delete(String id, Long operatorId) {
        LlmProviderCatalog.Catalog snapshot = catalog.read();
        List<LlmProviderDescriptor> next =
                snapshot.providers().stream()
                        .filter(existing -> !existing.id().equals(id))
                        .toList();
        if (next.size() == snapshot.providers().size()) {
            throw new BusinessException(ApiCode.NOT_FOUND, "provider 不存在：" + id);
        }
        if (id.equals(snapshot.activeId())) {
            // 激活中的条目是答疑等 AI 功能的当前数据源：先切再删，避免答疑瞬间失去数据源（三态降级兜底
            // 之外，管理端操作应显式无歧义）。
            throw new BusinessException(
                    ApiCode.VALIDATION_FAILED, "激活中的 provider 不能删除，请先激活其他 provider");
        }
        catalog.saveProviders(next, operatorId);
    }

    @Override
    public LlmProviderView activate(String id, Long operatorId) {
        LlmProviderCatalog.Catalog snapshot = catalog.read();
        LlmProviderDescriptor target =
                snapshot.providers().stream()
                        .filter(existing -> existing.id().equals(id))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ApiCode.NOT_FOUND, "provider 不存在：" + id));
        if (!id.equals(snapshot.activeId())) {
            catalog.saveActive(id, operatorId);
        }
        return toView(target, id);
    }

    /**
     * 解析协议 wire 值；未知值 40000（不静默映射，fail-closed 与目录读取侧同口径）。
     *
     * @param raw wire 值
     * @return 协议枚举
     */
    private static LlmProtocol requireProtocol(String raw) {
        return LlmProtocol.fromWire(raw)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ApiCode.VALIDATION_FAILED,
                                        "protocol 只允许 anthropic-messages / openai-chat / openai-responses"));
    }

    /**
     * 目录条目 → 管理端视图（hasKey 实时查 env，不落库）。
     *
     * @param descriptor 目录条目
     * @param activeId 当前激活 id
     * @return 条目视图
     */
    private LlmProviderView toView(LlmProviderDescriptor descriptor, String activeId) {
        String envKey = catalog.envKey(descriptor.id());
        return new LlmProviderView(
                descriptor.id(),
                descriptor.name(),
                Objects.requireNonNull(descriptor.protocol()).wire(),
                descriptor.baseUrl(),
                descriptor.model(),
                envKey != null && !envKey.isEmpty(),
                descriptor.id().equals(activeId));
    }
}
