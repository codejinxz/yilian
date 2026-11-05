package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.LlmUsage;

import org.springframework.data.jpa.repository.JpaRepository;

/** LLM 用量 Spring Data 仓库，只允许同包 JpaLlmUsageDao 使用。 */
public interface LlmUsageJpaRepository extends JpaRepository<LlmUsage, Long> {}
