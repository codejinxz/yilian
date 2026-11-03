package io.github.codejinxz.yilian.content.dao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.util.StringUtils;
import io.github.codejinxz.yilian.content.service.ContentAdminService;
import io.github.codejinxz.yilian.content.service.ContentImportService;
import io.github.codejinxz.yilian.content.service.dto.SentenceContent;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.entity.UserRole;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * keyword LIKE 通配符转义的语义集成测试 [AC-5]（015 修复轮 minor #5，真实 MySQL yilian_test）：
 *
 * <p>服务层 {@code StringUtils.escapeLike} + SQL 侧 {@code ESCAPE '\\'} 组合后，用户输入的 {@code %}/{@code _}
 * 必须是**字面量**而不是通配符——keyword={@code 0%_折} 只命中名字里真的含 「0%_折」的行，不得退化为前缀/任意匹配。覆盖三处 keyword
 * 拼接：JPQL（searchVisible）、 管理端 JPQL（searchAdmin）与管理端题目 XML（adminListSentences），用户检索同链路抽验。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.security.jwt.secret=test-only-yilian-jwt-secret-0123456789abcdef"
        })
@ActiveProfiles("local")
class LikeEscapeIntegrationTest {

    private static final Timestamp T0 = Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 10, 0, 0));

    @Autowired private BankDao bankDao;

    @Autowired private ContentImportService contentImportService;

    @Autowired private AppUserDao appUserDao;

    @Autowired private ContentAdminService contentAdminService;

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 测试库指向本机 yilian_test。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:mysql://localhost:3306/yilian_test?useUnicode=true&characterEncoding=utf8"
                                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                                + "&allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "123456");
    }

    /** 清空相关表并播种含通配符字面量的行；append-only 触发器摘除与重建同口径。 */
    @BeforeEach
    void cleanAndSeed() {
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_update");
        jdbcTemplate.update("DROP TRIGGER IF EXISTS trg_sentence_versions_no_delete");
        jdbcTemplate.update("UPDATE sentences SET current_version_id = NULL");
        jdbcTemplate.update("DELETE FROM import_batch_rows");
        jdbcTemplate.update("DELETE FROM sentence_versions");
        jdbcTemplate.update("DELETE FROM sentences");
        jdbcTemplate.update("DELETE FROM import_batches");
        jdbcTemplate.update("DELETE FROM sentence_banks");
        jdbcTemplate.update("DELETE FROM users WHERE id >= 800");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_sentence_versions_no_update BEFORE UPDATE ON sentence_versions"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'sentence_versions is immutable'");
        jdbcTemplate.update(
                "CREATE TRIGGER trg_sentence_versions_no_delete BEFORE DELETE ON sentence_versions"
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'sentence_versions is immutable'");

        insertBank(801, "全场0%_折扣专场");
        insertBank(802, "普通题库A");
        insertBank(803, "普通题库B");
        jdbcTemplate.update(
                "INSERT INTO users (id, email, username, password_hash, role, enabled)"
                        + " VALUES (801, 'wild%card@example.com', 'user_x_y', 'seeded', 'USER', TRUE)"
                        + " ON DUPLICATE KEY UPDATE enabled = TRUE");
    }

    /** 重建触发器由每用例入口处理；users 800+ 为本套件专用段。 */
    @AfterEach
    void cleanAfter() {
        jdbcTemplate.update("DELETE FROM users WHERE id >= 800");
    }

    /** [AC-5] searchVisible：keyword 含 %/_ 时按字面量匹配（匿名公开题库链路，JPQL escape '\\'）。 */
    @Test
    @DisplayName("[AC-5] searchVisible 的 %/_ 是字面量")
    void ac5_searchVisibleTreatsWildcardsLiterally() {
        var exact =
                bankDao.searchVisible(
                        null, StringUtils.escapeLike("0%_折"), null, PageRequest.of(0, 20));
        assertThat(exact.getTotalElements()).isEqualTo(1L);
        assertThat(exact.getContent().getFirst().getName()).isEqualTo("全场0%_折扣专场");

        // 未转义的 % 作为通配符的老语义不再成立：传入带裸 % 的字符串只按字面量找（这里转义后同上，
        // 而「0」作为子串能命中的行也必须是包含字面 0%_折 的那一行才对——用转义后的关键字双重确认）。
        var substring =
                bankDao.searchVisible(
                        null, StringUtils.escapeLike("折扣"), null, PageRequest.of(0, 20));
        assertThat(subsetNames(substring)).containsExactly("全场0%_折扣专场");
    }

    /** [AC-5] searchAdmin：管理端题库检索（名称/说明两列）同样按字面量匹配。 */
    @Test
    @DisplayName("[AC-5] searchAdmin 的 %/_ 是字面量")
    void ac5_searchAdminTreatsWildcardsLiterally() {
        var exact =
                bankDao.searchAdmin(
                        StringUtils.escapeLike("0%_折"), null, null, PageRequest.of(0, 20));
        assertThat(exact.getTotalElements()).isEqualTo(1L);
        assertThat(exact.getContent().getFirst().getId()).isEqualTo(801L);
    }

    /** [AC-5] 管理端题目检索（XML ESCAPE）：keyword 字面量命中，不退化为通配符。 */
    @Test
    @DisplayName("[AC-5] adminListSentences 的 %/_ 是字面量")
    void ac5_adminSentenceSearchTreatsWildcardsLiterally() {
        seedSentence(801, "价格打九折依然很好。", "Prices are discounted and remain attractive.");
        seedSentence(801, "全场0%_折扣只此一天。", "The literal zero percent discount lasts one day.");

        // 服务层内部做 LIKE 转义：这里传裸关键字，服务端把 %/_ 当字面量匹配。
        PageResponse<SentenceDetail> exact =
                contentAdminService.adminListSentences("0%_折扣", "801", null, null, 1, 20);
        assertThat(exact.total()).isEqualTo(1L);
        assertThat(exact.items().getFirst().zh()).isEqualTo("全场0%_折扣只此一天。");
    }

    /** [AC-5] 用户检索（AppUserJpaRepository JPQL）：username 里的 _ 按字面量匹配。 */
    @Test
    @DisplayName("[AC-5] 用户检索的 _ 是字面量")
    void ac5_userSearchTreatsUnderscoreLiterally() {
        var exact =
                appUserDao.search(
                        StringUtils.escapeLike("r_x_"),
                        UserRole.USER,
                        "all",
                        PageRequest.of(0, 20));
        assertThat(exact.getTotalElements()).isEqualTo(1L);
        assertThat(exact.getContent().getFirst().getUsername()).isEqualTo("user_x_y");

        // 裸 _ 若仍是通配符，「user」+ 任意两字符会误命中；转义后按字面量找不到。
        var literalOnly =
                appUserDao.search(
                        StringUtils.escapeLike("user"),
                        UserRole.USER,
                        "all",
                        PageRequest.of(0, 20));
        assertThat(subsetUsernames(literalOnly)).containsExactly("user_x_y");
    }

    /**
     * 插入题库行（已发布公开）。
     *
     * @param id 题库 id
     * @param name 题库名称
     */
    private void insertBank(int id, String name) {
        jdbcTemplate.update(
                "INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status,"
                        + " version, published_at, created_by, updated_by, created_at, updated_at,"
                        + " deleted_at, deleted_by)"
                        + " VALUES (?, NULL, ?, NULL, 'PUBLIC', 'PUBLISHED', 0, ?, NULL, NULL, ?, ?,"
                        + " NULL, NULL)",
                id,
                name,
                T0,
                T0,
                T0);
    }

    /**
     * 经真实导入链路播种一条已发布题目（管理端检索链路用；指纹与快照列由导入服务统一生成）。
     *
     * @param bankId 题库 id
     * @param zh 中文句（可含 %/_ 等通配符字面量）
     * @param ref 参考译文
     */
    private void seedSentence(long bankId, String zh, String ref) {
        String batchId = "like-batch-" + bankId + "-" + zh.hashCode();
        jdbcTemplate.update(
                "INSERT INTO import_batches (id, bank_id, format, original_filename, storage_key,"
                        + " content_hash, size_bytes, status, created_by, created_by_name, created_at)"
                        + " VALUES (?, ?, 'JSON', 'it.json', 'imports/it.json', ?, 10, 'COMMITTED',"
                        + " 9, 'it-admin', ?)",
                batchId,
                bankId,
                "a".repeat(64),
                T0);
        contentImportService.importSentences(
                bankId,
                batchId,
                List.of(
                        new SentenceContent(
                                "s_" + zh.hashCode(),
                                zh,
                                ref,
                                "这是转义集成测试的解析说明，长度满足最小要求。",
                                List.of(
                                        new SentenceChunk(
                                                0, zh.substring(0, 1), List.of(ref), "名词", null)),
                                List.of(0),
                                List.of("名词"),
                                null,
                                null,
                                null)),
                9L,
                T0.toInstant());
    }

    /**
     * 提取分页结果的题库名列表。
     *
     * @param page 分页结果
     * @return 名称列表
     */
    private List<String> subsetNames(org.springframework.data.domain.Page<?> page) {
        return page.getContent().stream()
                .map(
                        bank ->
                                ((io.github.codejinxz.yilian.content.entity.SentenceBank) bank)
                                        .getName())
                .toList();
    }

    /**
     * 提取分页结果的用户名列表。
     *
     * @param page 分页结果
     * @return 用户名列表
     */
    private List<String> subsetUsernames(org.springframework.data.domain.Page<?> page) {
        return page.getContent().stream()
                .map(
                        user ->
                                ((io.github.codejinxz.yilian.system.entity.AppUser) user)
                                        .getUsername())
                .toList();
    }
}
