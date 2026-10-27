import { create } from "zustand";
import { persist } from "zustand/middleware";

/** 主题模式：稿纸 / 夜读 / 跟随系统。「跟随系统」是本机偏好，不写入服务端设置。 */
export type ThemeMode = "paper" | "night" | "system";

/** 实际渲染的主题；「跟随系统」经 matchMedia 解析后只落在二值上，CSS 变量只认这两个值。 */
export type ResolvedTheme = "paper" | "night";

/**
 * 批改方式。当前版本全部为本地判分：选 ALWAYS_MODEL 也按 AUTO 执行，
 * 这是给 M3 深度批改预留的本地偏好，先落 store 不参与判分。
 */
export type GradingMode = "LOCAL" | "AUTO" | "ALWAYS_MODEL";

/** 评分标准的游客本地镜像；取值与服务端 UserSettings["ratingStandard"] 一致，登录后仍以服务端为权威。 */
export type LocalRatingStandard = "TEM8" | "CATTI" | "IELTS";

/** persist 落盘的字段形状；与组件状态解耦，版本迁移只处理这一层。 */
interface PersistedSettings {
  theme: ThemeMode;
  gradingMode: GradingMode;
  localOnly: boolean;
  timezoneLocal: string | null;
  ratingStandardLocal: LocalRatingStandard;
}

interface SettingsState extends PersistedSettings {
  /** 立即应用主题模式；system 按当前系统明暗解析，系统变化监听在本模块底部注册。 */
  setTheme: (mode: ThemeMode) => void;
  /**
   * 用服务端主题同步本地偏好；本地是「跟随系统」时忽略。
   * 为什么忽略：跟随系统是用户显式的本机选择，后台缓存刷新不得悄悄覆盖；
   * 服务端权威在用户下次显式选择稿纸/夜读（经设置页 PUT）时重新建立。
   * @param theme 服务端返回的主题，只可能是二值
   */
  syncTheme: (theme: ResolvedTheme) => void;
  /** 记录批改方式偏好；当前版本只存储不参与判分。 */
  setGradingMode: (mode: GradingMode) => void;
  /** 记录「仅本地批改」开关；当前版本全部本地判分，开关为深度批改预留。 */
  setLocalOnly: (value: boolean) => void;
  /** 记录游客时区；本期仅存储，不参与本地引擎计算。null 表示未设置。 */
  setTimezoneLocal: (value: string | null) => void;
  /** 记录游客评分标准镜像；登录用户走服务端，此值仅在游客态展示与保存。 */
  setRatingStandardLocal: (value: LocalRatingStandard) => void;
}

/** 迁移失败与首装时的兜底值；与原型默认一致（grading auto / theme paper / rubric tem8）。 */
const DEFAULTS: PersistedSettings = {
  theme: "paper",
  gradingMode: "AUTO",
  localOnly: false,
  timezoneLocal: null,
  ratingStandardLocal: "TEM8",
};

/** jsdom / 无 matchMedia 环境下为 null，「跟随系统」按稿纸解析，避免测试与 SSR 崩溃。 */
const prefersDark =
  typeof window !== "undefined" && typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;

/**
 * 把主题模式解析为实际渲染值；「跟随系统」查系统明暗，其余原样返回。
 * @param mode 用户选择的主题模式
 * @returns 实际应用到根节点的主题
 */
function resolveTheme(mode: ThemeMode): ResolvedTheme {
  if (mode !== "system") {
    return mode;
  }
  return prefersDark?.matches ? "night" : "paper";
}

/**
 * 把主题写到根节点；夜读主题只覆盖 CSS 变量，页面组件不需要区分主题。
 * @param mode 主题模式（system 先解析为二值再落 dataset）
 */
function applyTheme(mode: ThemeMode): void {
  if (typeof document !== "undefined") {
    document.documentElement.dataset.theme = resolveTheme(mode);
  }
}

/**
 * 迁移旧落盘数据：v0 只存 theme（paper/night），v2 起带全部本地偏好。
 * 为什么逐字段校验：localStorage 可能被旧版本、用户脚本或序列化失败弄脏，
 * 单个坏字段回默认值即可，不能让整份偏好丢失或让首屏崩掉。
 * @param persisted 旧落盘数据
 * @returns 迁移后的完整本地偏好
 */
function migratePersisted(persisted: unknown): PersistedSettings {
  if (typeof persisted !== "object" || persisted === null) {
    return { ...DEFAULTS };
  }
  const raw = persisted as Partial<Record<keyof PersistedSettings, unknown>>;
  const theme =
    raw.theme === "paper" || raw.theme === "night" || raw.theme === "system"
      ? raw.theme
      : DEFAULTS.theme;
  const gradingMode =
    raw.gradingMode === "LOCAL" || raw.gradingMode === "AUTO" || raw.gradingMode === "ALWAYS_MODEL"
      ? raw.gradingMode
      : DEFAULTS.gradingMode;
  const ratingStandardLocal =
    raw.ratingStandardLocal === "TEM8" ||
    raw.ratingStandardLocal === "CATTI" ||
    raw.ratingStandardLocal === "IELTS"
      ? raw.ratingStandardLocal
      : DEFAULTS.ratingStandardLocal;
  const timezoneLocal =
    typeof raw.timezoneLocal === "string" && raw.timezoneLocal.length > 0
      ? raw.timezoneLocal
      : DEFAULTS.timezoneLocal;
  return {
    theme,
    gradingMode,
    localOnly: typeof raw.localOnly === "boolean" ? raw.localOnly : DEFAULTS.localOnly,
    timezoneLocal,
    ratingStandardLocal,
  };
}

/**
 * 本地 UI 偏好：主题（含跟随系统）、批改方式、仅本地开关、游客时区与评分标准镜像。
 * 为什么放 Zustand：这些是纯本机偏好，不进 Query 缓存；登录用户的 theme / dailyGoal /
 * ratingStandard / timezone 仍以服务端 settings 为权威，由设置页经 PUT 同步。
 */
export const useSettingsStore = create<SettingsState>()(
  persist(
    (set, get) => ({
      theme: DEFAULTS.theme,
      gradingMode: DEFAULTS.gradingMode,
      localOnly: DEFAULTS.localOnly,
      timezoneLocal: DEFAULTS.timezoneLocal,
      ratingStandardLocal: DEFAULTS.ratingStandardLocal,
      setTheme: (mode) => {
        applyTheme(mode);
        set({ theme: mode });
      },
      syncTheme: (theme) => {
        if (get().theme === "system") {
          return;
        }
        if (get().theme !== theme) {
          applyTheme(theme);
          set({ theme });
        }
      },
      setGradingMode: (mode) => {
        set({ gradingMode: mode });
      },
      setLocalOnly: (value) => {
        set({ localOnly: value });
      },
      setTimezoneLocal: (value) => {
        set({ timezoneLocal: value });
      },
      setRatingStandardLocal: (value) => {
        set({ ratingStandardLocal: value });
      },
    }),
    {
      name: "yilian.settings",
      // v0：仅 theme；v2：新增批改方式、仅本地、游客时区与评分标准镜像。
      version: 2,
      migrate: migratePersisted,
      partialize: (state): PersistedSettings => ({
        theme: state.theme,
        gradingMode: state.gradingMode,
        localOnly: state.localOnly,
        timezoneLocal: state.timezoneLocal,
        ratingStandardLocal: state.ratingStandardLocal,
      }),
      onRehydrateStorage: () => (state) => {
        if (state) {
          applyTheme(state.theme);
        }
      },
    },
  ),
);

// 「跟随系统」要实时跟随系统明暗：注册一次全局监听即可，与组件生命周期解耦；
// 仅在当前模式是 system 时重算，纸面/夜读的显式选择不受系统切换影响。
if (prefersDark) {
  prefersDark.addEventListener("change", () => {
    if (useSettingsStore.getState().theme === "system") {
      applyTheme("system");
    }
  });
}
