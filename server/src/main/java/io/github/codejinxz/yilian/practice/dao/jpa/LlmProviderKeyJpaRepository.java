package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.LlmProviderKey;

import org.springframework.data.jpa.repository.JpaRepository;

/** provider 入库密钥 Spring Data 仓库，只允许同包 JpaLlmProviderKeyDao 使用。 */
public interface LlmProviderKeyJpaRepository extends JpaRepository<LlmProviderKey, String> {}
