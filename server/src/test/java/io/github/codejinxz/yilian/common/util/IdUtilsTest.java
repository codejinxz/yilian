package io.github.codejinxz.yilian.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** IdUtils 生成值的格式、版本与唯一性。 */
class IdUtilsTest {

    private static final Pattern UUID_V4_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern COMPACT_PATTERN = Pattern.compile("[0-9a-f]{32}");

    @Test
    void newUuidHasStandardShape() {
        assertThat(IdUtils.newUuid()).matches(UUID_V4_PATTERN);
    }

    @Test
    void newUuidIsParsableVersion4() {
        UUID parsed = UUID.fromString(IdUtils.newUuid());
        assertThat(parsed.version()).isEqualTo(4);
        assertThat(parsed.variant()).isEqualTo(2);
    }

    @Test
    void newCompactIdHasNoHyphenAnd32Chars() {
        String compact = IdUtils.newCompactId();
        assertThat(compact).hasSize(32).matches(COMPACT_PATTERN).doesNotContain("-");
    }

    @Test
    void newCompactIdCanBeRebuiltIntoUuid() {
        String compact = IdUtils.newCompactId();
        String dashed =
                compact.substring(0, 8)
                        + "-"
                        + compact.substring(8, 12)
                        + "-"
                        + compact.substring(12, 16)
                        + "-"
                        + compact.substring(16, 20)
                        + "-"
                        + compact.substring(20);
        assertThat(UUID.fromString(dashed).toString().replace("-", "")).isEqualTo(compact);
    }

    @Test
    void newUuidIsUniqueAcrossCalls() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            values.add(IdUtils.newUuid());
        }
        assertThat(values).hasSize(100);
    }

    @Test
    void newCompactIdIsUniqueAcrossCalls() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            values.add(IdUtils.newCompactId());
        }
        assertThat(values).hasSize(100);
    }
}
