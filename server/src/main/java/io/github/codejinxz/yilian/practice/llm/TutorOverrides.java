package io.github.codejinxz.yilian.practice.llm;

import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 答疑 LLM 覆盖配置存取（app_settings 键 {@code llm.tutor}，028）：值 JSON 从 017 的纯开关
 * {@code {"schemaVersion":1,"enabled":bool}} 扩展为 {@code {"schemaVersion":1,"enabled":bool,"baseUrl":"…","model":"…"}}——
 * baseUrl/model 是 legacy 形态（目录空时）的覆盖值，null=无覆盖（回退 {@code YILIAN_LLM_*} env）。
 *
 * <p>fail-closed 解析：键不存在、JSON 损坏、版本不识别一律按「关闭 + 无覆盖」处理，不让脏配置
 * 打挂答疑三态门或注册表装配。兼容 017 早期裸布尔形态（{@code true}/{@code false}）：按开关值
 * 解析、无覆盖。enabled 的语义与 017 一致（答疑开关），baseUrl/model 只在目录空时参与装配
 * （022 语义不变：目录非空时覆盖处于休眠态，激活条目高于一切 legacy 形态）。
 *
 * <p>本类是注册表（{@code LlmClientRegistryImpl} 装配覆盖轨）与管理端配置服务
 * （{@code TutorServiceImpl} 读写开关与覆盖）的共享解析口——同一个键只维护一套形状，避免双解析漂移。
 */
@Component
public class TutorOverrides {

    private static final Logger log = LoggerFactory.getLogger(TutorOverrides.class);

    /** app_settings 配置键：答疑开关 + legacy 形态覆盖值。 */
    public static final String SETTING_KEY = "llm.tutor";

    /** 存储值当前结构版本；解析遇到其他版本按「关闭 + 无覆盖」处理（fail-closed）。 */
    public static final int SCHEMA_VERSION = 1;

    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;

    /**
     * 注入依赖。
     *
     * @param appSettingService 系统配置存取（app_settings 键值）
     * @param objectMapper JSON 解析（配置值）
     */
    public TutorOverrides(AppSettingService appSettingService, ObjectMapper objectMapper) {
        this.appSettingService = appSettingService;
        this.objectMapper = objectMapper;
    }

    /**
     * 存储结构（app_settings 键 {@code llm.tutor} 的值 JSON）：baseUrl/model 为 null 表示无该项覆盖
     * （生效值回退 legacy 环境变量）。
     *
     * @param schemaVersion 结构版本
     * @param enabled 答疑开关（017 语义不变）
     * @param baseUrl 端点覆盖值；null=无覆盖（回退 YILIAN_LLM_BASE_URL）
     * @param model 模型名覆盖值；null=无覆盖（回退 YILIAN_LLM_MODEL）
     */
    public record Stored(int schemaVersion, boolean enabled, String baseUrl, String model) {

        /**
         * 是否有端点覆盖（非 null 且非空白）。
         *
         * @return 有端点覆盖返回 true
         */
        public boolean hasBaseUrl() {
            return baseUrl != null && !baseUrl.isBlank();
        }

        /**
         * 是否有模型名覆盖（非 null 且非空白）。
         *
         * @return 有模型名覆盖返回 true
         */
        public boolean hasModel() {
            return model != null && !model.isBlank();
        }

        /**
         * 是否存在任意覆盖项（端点或模型名任一）。
         *
         * @return 有任一覆盖返回 true
         */
        public boolean hasOverride() {
            return hasBaseUrl() || hasModel();
        }

        /**
         * 覆盖项状态指纹（端点|模型名，空白项以空串占位）：注册表用它把「覆盖变更」纳入客户端
         * 缓存失效——只翻转 enabled 不产生新指纹（轨道不变，无需换轨）。
         *
         * @return 指纹串；无任何覆盖时为空串
         */
        public String overrideStamp() {
            if (!hasOverride()) {
                return "";
            }
            return (baseUrl == null ? "" : baseUrl) + "|" + (model == null ? "" : model);
        }

        /**
         * fail-closed 兜底值：关闭 + 无覆盖。
         *
         * @return 兜底存储值
         */
        public static Stored disabled() {
            return new Stored(SCHEMA_VERSION, false, null, null);
        }
    }

    /**
     * 读取存储配置（fail-closed，永不返回 null）：键不存在、JSON 损坏、版本不识别按
     * {@link Stored#disabled()} 处理；017 早期裸布尔形态按开关值兼容解析（无覆盖）。
     *
     * @return 存储配置
     */
    public Stored read() {
        Optional<String> raw = appSettingService.findValue(SETTING_KEY);
        if (raw.isEmpty()) {
            return Stored.disabled();
        }
        try {
            Stored parsed = objectMapper.readValue(raw.get(), Stored.class);
            if (parsed.schemaVersion() != SCHEMA_VERSION) {
                return Stored.disabled();
            }
            return parsed;
        } catch (JacksonException objectFailure) {
            // 017 早期裸布尔形态（值为 true/false 而非对象）：按开关值兼容，不当作损坏丢弃。
            try {
                boolean legacyEnabled = objectMapper.readValue(raw.get(), Boolean.class);
                return new Stored(SCHEMA_VERSION, legacyEnabled, null, null);
            } catch (JacksonException booleanFailure) {
                log.warn(
                        "llm.tutor 配置损坏，按关闭+无覆盖处理（fail-closed）：error={}",
                        objectFailure.getClass().getSimpleName());
                return Stored.disabled();
            }
        }
    }

    /**
     * 序列化存储值（写入方统一走本方法，保证与 {@link #read()} 的形状同源）。
     *
     * @param stored 待写入的存储值
     * @return JSON 文本
     */
    public String write(Stored stored) {
        return objectMapper.writeValueAsString(stored);
    }
}
