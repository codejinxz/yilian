import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { ErrorState, LoadingBlock, Notice } from "@/components/feedback";
import { clearSession } from "@/features/auth/clear-session";
import { ConfirmDialog } from "@/features/settings/confirm-dialog";
import { type GradingMode, type ThemeMode, useSettingsStore } from "@/features/settings/store";
import { api } from "@/lib/api";
import { updateSettings } from "@/lib/api/account";
import {
  clearLocalData,
  exportLocalData,
  getTodaySnapshot,
  resetPlacementState,
  setDailyGoal,
} from "@/lib/local-engine";
import { isMockEnabled } from "@/lib/mock-flag";
import { checkForUpdate } from "@/lib/pwa";
import { meQueryOptions, queryKeys, settingsQueryOptions } from "@/lib/query";
import type { UpdateSettingsRequest, UserSettings } from "@/lib/schemas/account";
import { cn } from "@/lib/utils";

const COMMON_TIMEZONES = [
  "Asia/Shanghai",
  "Asia/Tokyo",
  "Asia/Singapore",
  "Asia/Hong_Kong",
  "Asia/Taipei",
  "Europe/London",
  "Europe/Paris",
  "America/New_York",
  "America/Chicago",
  "America/Los_Angeles",
  "Australia/Sydney",
  "UTC",
];

/** 原型「删除账号」的原因选项；文案照原型 askReason。 */
const DELETE_REASONS = ["不再使用", "隐私顾虑", "其他"] as const;

/** 快捷键表；文案照原型 kbd-table。 */
const SHORTCUTS: Array<readonly [string, string]> = [
  ["提交 / 下一句", "⌘ / Ctrl + ↵"],
  ["焦点跳下一个空", "↵"],
  ["切换模式", "1 / 2"],
];

/**
 * 页头：同原型 view-settings——30px 衬线标题 + 13px 说明（全局 .page-title/.page-sub）。
 * 原型无引题（kicker），各加载/错误分支与主渲染共用同一份，保证任何状态页头不跳动。
 */
function SettingsPageHeader() {
  return (
    <header>
      <h1 className="page-title">设置</h1>
      <p className="page-sub">本地优先。能离线的都离线，需要模型的地方才联网。</p>
    </header>
  );
}

/**
 * 原型 setting 行：左标签 170px + 右内容 1fr，≤760px 退化为单列（媒体查询）。
 * @param props label 左侧栏目标签、children 右侧内容
 */
function SettingRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <section className="grid gap-3 border-b border-rule py-6 min-[761px]:grid-cols-[170px_minmax(0,1fr)] min-[761px]:gap-7">
      <h2 className="text-[13px] font-normal leading-[1.5] text-ink">{label}</h2>
      <div className="min-w-0">{children}</div>
    </section>
  );
}

/**
 * 原型 radio 行：圆点 + 标题 + 小字说明；accent 用朱红，输入框 margin-top 3px 同原型。
 * @param props name 组名、value 值、checked 选中态、title 标题、desc 小字说明、note 朱红备注（如实注明当前限制）、onChange 选中回调
 */
function RadioOption({
  name,
  value,
  checked,
  title,
  desc,
  note,
  onChange,
}: {
  name: string;
  value: string;
  checked: boolean;
  title: string;
  desc?: string;
  note?: string;
  onChange: () => void;
}) {
  return (
    <label className="flex cursor-pointer items-start gap-2.5 text-[13px] leading-[1.5] text-ink-muted">
      <input
        type="radio"
        name={name}
        value={value}
        checked={checked}
        onChange={onChange}
        className="mt-[3px] accent-vermilion"
      />
      <span>
        <span className="text-ink">{title}</span>
        {desc ? (
          <span className="mt-1 block text-[11px] leading-[1.65] text-ink-faint">{desc}</span>
        ) : null}
        {note ? (
          <span className="mt-1 block text-[11px] leading-[1.65] text-vermilion-deep">{note}</span>
        ) : null}
      </span>
    </label>
  );
}

/**
 * 设置页：按原型 view-settings 全量对齐 —— 账号、批改方式、外观、每日目标、评分标准、
 * 提醒、时区、更多、数据与隐私、数据、快捷键。
 *
 * 数据分流：登录用户的 theme / dailyGoal / ratingStandard / timezone 以服务端 settings 为权威，
 * 每次选择立即 PUT（原型没有保存按钮）；游客全部走本机（Zustand 本地偏好 + local-engine）。
 * me 查询失败（401 等）一律按游客处理：本页不跳登录，也不重试轰炸（重试策略由 me 查询全局配置管）。
 */
export function SettingsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  // me 查询与 AppShell 共享缓存（同 key），不会多发请求；401 表示游客。
  const me = useQuery(meQueryOptions);
  const user = me.data ?? null;
  // 服务端设置只在登录后拉取；游客保持 disabled，不发注定 401 的请求。
  const settingsQuery = useQuery({ ...settingsQueryOptions, enabled: Boolean(user) });
  const serverSettings = settingsQuery.data ?? null;

  const theme = useSettingsStore((state) => state.theme);
  const setTheme = useSettingsStore((state) => state.setTheme);
  const syncTheme = useSettingsStore((state) => state.syncTheme);
  const gradingMode = useSettingsStore((state) => state.gradingMode);
  const setGradingMode = useSettingsStore((state) => state.setGradingMode);
  const localOnly = useSettingsStore((state) => state.localOnly);
  const setLocalOnly = useSettingsStore((state) => state.setLocalOnly);
  const timezoneLocal = useSettingsStore((state) => state.timezoneLocal);
  const setTimezoneLocal = useSettingsStore((state) => state.setTimezoneLocal);
  const ratingStandardLocal = useSettingsStore((state) => state.ratingStandardLocal);
  const setRatingStandardLocal = useSettingsStore((state) => state.setRatingStandardLocal);

  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const toastTimerRef = useRef<number | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [guestGoal, setGuestGoal] = useState<number | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [clearOpen, setClearOpen] = useState(false);
  const [clearPending, setClearPending] = useState(false);
  const [exporting, setExporting] = useState(false);

  // 游客每日目标读本地引擎；引擎没有单独的 getter，今日快照里的 goal 就是权威值。
  useEffect(() => {
    if (user) {
      return;
    }
    let cancelled = false;
    void getTodaySnapshot()
      .then((snapshot) => {
        if (!cancelled) {
          setGuestGoal(snapshot.goal);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setGuestGoal(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [user]);

  // 卸载时清掉未触发的 toast 定时器，避免对已卸载组件 setState。
  useEffect(
    () => () => {
      if (toastTimerRef.current !== null) {
        window.clearTimeout(toastTimerRef.current);
      }
    },
    [],
  );

  /**
   * 页内轻提示；原型 toast 的替代实现（全局没有 toast 基础设施），role=status 供读屏播报，3 秒自动消失。
   * @param message 提示文案
   */
  function showToast(message: string) {
    setToastMessage(message);
    if (toastTimerRef.current !== null) {
      window.clearTimeout(toastTimerRef.current);
    }
    toastTimerRef.current = window.setTimeout(() => setToastMessage(null), 3000);
  }

  const save = useMutation({
    mutationFn: updateSettings,
    onSuccess: (settings) => {
      queryClient.setQueryData(queryKeys.settings, settings);
      syncTheme(settings.theme);
      setSaveError(null);
    },
    onError: (error) => {
      setSaveError(
        `保存失败：${error instanceof Error ? error.message : "请稍后重试"}。改动未同步到服务端，可重新选择一次。`,
      );
    },
  });

  /**
   * 登录用户的即时保存：「选择即生效」，失败用行内提示兜底，不弹窗打断。
   * @param patch 本次变化的字段；服务端是部分更新
   */
  function updateServerField(patch: UpdateSettingsRequest) {
    setSaveError(null);
    save.mutate(patch);
  }

  const logout = useMutation({
    mutationFn: () => api.logout(),
    onSettled: () => {
      // 容错：无论请求成败都清本地会话并留在设置页变游客态（不跳转）；
      // 服务端吊销失败时 refresh 会话也会自然过期，不阻塞用户。
      clearSession(queryClient);
      showToast("已退出登录");
    },
  });

  const resetDemo = useMutation({
    mutationFn: async () => {
      const { resetMockDb } = await import("@/mocks/db");
      resetMockDb();
    },
    onSuccess: () => {
      clearSession(queryClient);
      void navigate({ to: "/login", replace: true });
    },
  });

  /**
   * 切换批改方式；纯本地偏好，当前版本只存储不参与判分（页面上已注明）。
   * @param mode 仅本地 / 自动 / 总是模型
   */
  function handleGradingChange(mode: GradingMode) {
    setGradingMode(mode);
    showToast(
      mode === "LOCAL"
        ? "批改方式：仅本地"
        : mode === "AUTO"
          ? "批改方式：自动"
          : "批改方式：总是模型",
    );
  }

  /**
   * 切换主题。跟随系统是本机偏好（服务端 schema 只接受纸面/夜读），不发起 PUT；
   * 纸面/夜读在登录态经 PUT 同步到服务端。
   * @param mode 主题模式
   */
  function handleThemeChange(mode: ThemeMode) {
    setTheme(mode);
    if (mode === "system") {
      showToast("「跟随系统」保存在本机；账号仍记录纸面 / 夜读。");
      return;
    }
    if (user) {
      updateServerField({ theme: mode });
    }
  }

  /**
   * 切换每日目标；登录用户走服务端，游客写本地引擎（今日启动台读同一份值）。
   * @param goal 1 / 3 / 5 句
   */
  function handleGoalChange(goal: 1 | 3 | 5) {
    if (user) {
      updateServerField({ dailyGoal: goal });
    } else {
      setDailyGoal(goal);
      setGuestGoal(goal);
    }
    showToast(`每日目标：每天 ${goal} 句`);
  }

  /**
   * 切换评分标准；登录用户走服务端，游客存本地镜像。
   * @param value 专八 / CATTI / 雅思
   */
  function handleRatingChange(value: UserSettings["ratingStandard"]) {
    if (user) {
      updateServerField({ ratingStandard: value });
    } else {
      setRatingStandardLocal(value);
    }
    showToast("评分标准已切换");
  }

  /** 真实导出：本地引擎全量数据（含题库缓存）下载为 yilian-export.json。 */
  async function handleExport() {
    setExporting(true);
    try {
      const data = await exportLocalData();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "yilian-export.json";
      anchor.click();
      URL.revokeObjectURL(url);
      showToast("已导出 JSON");
    } catch {
      showToast("导出失败：本地数据暂时读不出来，请重试。");
    } finally {
      setExporting(false);
    }
  }

  /**
   * 确认清空本地数据：引擎清四类记录、题库缓存、定级与目标（定级标记引擎已覆盖）；
   * 练习草稿与离线队列不在引擎管辖内，但漏清离线队列会被壳层的联网自动补交在
   * 刷新后写回刚清空的引擎（与「不可恢复」的承诺矛盾），故一并清掉；
   * 本机 UI 偏好（主题/批改方式等）同清，然后整页刷新。
   */
  function handleClearConfirm() {
    setClearOpen(false);
    setClearPending(true);
    void clearLocalData()
      .then(() => {
        try {
          window.localStorage.removeItem("yilian.settings");
          window.localStorage.removeItem("yilian.offline-attempts.v1");
          for (const key of Object.keys(window.localStorage)) {
            if (key.startsWith("yilian.drafts.")) {
              window.localStorage.removeItem(key);
            }
          }
        } catch {
          // localStorage 被禁用时忽略：练习数据已清，刷新后按默认值重建。
        }
        window.location.reload();
      })
      .catch(() => {
        setClearPending(false);
        showToast("清空失败：本地存储暂时不可用，请稍后重试。");
      });
  }

  /** 重新定级：清掉本地定级状态后跳定级页；壳层路由的定级门会自动接管后续导航。 */
  function handleRePlacement() {
    resetPlacementState();
    void navigate({ to: "/placement" });
  }

  /** 真调 Notification API；结果用页内提示反馈，拒绝/不支持各给对应文案。 */
  function handleNotify() {
    if (typeof window === "undefined" || !("Notification" in window)) {
      showToast("当前浏览器不支持通知（演示）。");
      return;
    }
    if (Notification.permission === "granted") {
      showToast("已获得通知权限，正式版会在这里试发一条。");
      return;
    }
    void Notification.requestPermission().then((result) => {
      if (result === "granted") {
        showToast("已获得通知权限。每天最多一条，默认关闭。");
      } else if (result === "denied") {
        showToast("通知权限被拒绝，可在浏览器设置中重新开启。");
      } else {
        showToast("本次未授权，可稍后再试。");
      }
    });
  }

  // 游客没有设置过时区时按缺省展示；服务端时区登录后权威。
  const currentTimezone =
    user && serverSettings ? serverSettings.timezone : (timezoneLocal ?? "Asia/Shanghai");
  const currentGoal = user && serverSettings ? serverSettings.dailyGoal : (guestGoal ?? 3);
  const currentRating =
    user && serverSettings ? serverSettings.ratingStandard : ratingStandardLocal;
  // 当前值不在常用列表（如服务端配置了其他时区）时补进候选，保证下拉始终显示权威值而不是空白。
  const timezoneOptions = COMMON_TIMEZONES.includes(currentTimezone)
    ? COMMON_TIMEZONES
    : [currentTimezone, ...COMMON_TIMEZONES];

  /**
   * 切换时区；候选集都是合法 IANA 名称，onChange 即提交（登录用户 PUT 服务端，游客落本机）。
   * @param next 选中的 IANA 时区
   */
  function handleTimezoneChange(next: string) {
    if (next === currentTimezone) {
      return;
    }
    if (user) {
      updateServerField({ timezone: next });
    } else {
      setTimezoneLocal(next);
    }
    showToast(`时区已切换为 ${next}`);
  }

  if (me.isPending) {
    return (
      <div>
        <SettingsPageHeader />
        <LoadingBlock label="正在确认登录状态…" rows={4} />
      </div>
    );
  }

  if (user && settingsQuery.isPending) {
    return (
      <div>
        <SettingsPageHeader />
        <LoadingBlock label="正在读取设置…" rows={4} />
      </div>
    );
  }

  if (user && settingsQuery.isError) {
    return (
      <div>
        <SettingsPageHeader />
        <ErrorState error={settingsQuery.error} onRetry={() => void settingsQuery.refetch()} />
      </div>
    );
  }

  return (
    <div>
      <SettingsPageHeader />

      {saveError ? (
        <Notice tone="danger" className="mt-6">
          {saveError}
        </Notice>
      ) : null}

      {/* 账号：登录态显示身份（含邮箱投影）与退出入口；游客给登录入口。 */}
      <div>
        <SettingRow label="账号">
          {user ? (
            <div>
              <div className="flex items-center gap-2 text-sm text-ink">
                <span>{user.username}</span>
                <span
                  className={cn(
                    "inline-flex items-center whitespace-nowrap rounded-[2px] border px-2 py-1 font-mono text-[10px] tracking-[0.1em]",
                    user.role === "ADMIN"
                      ? "border-vermilion/40 text-vermilion"
                      : "border-rule text-ink-faint",
                  )}
                >
                  {user.role === "ADMIN" ? "管理员" : "用户"}
                </span>
              </div>
              {user.email ? (
                <p className="mt-1 font-mono text-[10px] text-ink-faint">{user.email}</p>
              ) : null}
              <div className="mt-4">
                <button
                  type="button"
                  className="linkish"
                  disabled={logout.isPending}
                  onClick={() => logout.mutate()}
                >
                  {logout.isPending ? "退出中…" : "退出登录"}
                </button>
              </div>
            </div>
          ) : (
            <div>
              <p className="text-[13px] text-ink-muted">当前是游客，数据只存在这台设备上。</p>
              <div className="mt-3.5">
                <Link to="/login" className="linkish">
                  登录或注册
                </Link>
              </div>
            </div>
          )}
        </SettingRow>

        <SettingRow label="批改方式">
          <div className="space-y-3.5">
            <RadioOption
              name="grading"
              value="LOCAL"
              checked={gradingMode === "LOCAL"}
              title="仅本地"
              desc="要点匹配 + 词级 diff。离线可用，零成本。"
              onChange={() => handleGradingChange("LOCAL")}
            />
            <RadioOption
              name="grading"
              value="AUTO"
              checked={gradingMode === "AUTO"}
              title="自动（推荐）"
              desc="先本地出结果，置信度低时再调模型。"
              onChange={() => handleGradingChange("AUTO")}
            />
            <RadioOption
              name="grading"
              value="ALWAYS_MODEL"
              checked={gradingMode === "ALWAYS_MODEL"}
              title="总是模型"
              desc="每次都走 LLM，最准也最慢。"
              note="当前版本全部为本地判分，选择后暂按「自动」执行。"
              onChange={() => handleGradingChange("ALWAYS_MODEL")}
            />
          </div>
        </SettingRow>

        <SettingRow label="外观">
          <div className="space-y-3.5">
            <RadioOption
              name="theme"
              value="paper"
              checked={theme === "paper"}
              title="稿纸"
              desc="暖白纸底，日常用。"
              onChange={() => handleThemeChange("paper")}
            />
            <RadioOption
              name="theme"
              value="night"
              checked={theme === "night"}
              title="夜读"
              desc="低亮度的暖黑，晚上不刺眼。"
              onChange={() => handleThemeChange("night")}
            />
            <RadioOption
              name="theme"
              value="system"
              checked={theme === "system"}
              title="跟随系统"
              desc="系统切暗色时一起切。"
              onChange={() => handleThemeChange("system")}
            />
          </div>
        </SettingRow>

        <SettingRow label="每日目标">
          <div className="space-y-3.5">
            {([1, 3, 5] as const).map((goal) => (
              <RadioOption
                key={goal}
                name="goal"
                value={String(goal)}
                checked={currentGoal === goal}
                title={`每天 ${goal} 句`}
                onChange={() => handleGoalChange(goal)}
              />
            ))}
          </div>
        </SettingRow>

        <SettingRow label="评分标准">
          <div className="space-y-3.5">
            <RadioOption
              name="rubric"
              value="TEM8"
              checked={currentRating === "TEM8"}
              title="专八"
              desc="忠实 60 / 语言适切 40"
              onChange={() => handleRatingChange("TEM8")}
            />
            <RadioOption
              name="rubric"
              value="CATTI"
              checked={currentRating === "CATTI"}
              title="CATTI"
              desc="错译漏译扣分制"
              onChange={() => handleRatingChange("CATTI")}
            />
            <RadioOption
              name="rubric"
              value="IELTS"
              checked={currentRating === "IELTS"}
              title="雅思"
              desc="TR / CC / LR / GRA 各 25%"
              onChange={() => handleRatingChange("IELTS")}
            />
          </div>
        </SettingRow>

        <SettingRow label="提醒">
          <div>
            <p className="text-[13px] leading-[1.7] text-ink-muted">
              PWA 通知只在安装后可用；iOS 需要手动授权，每天最多一条，默认关闭。
            </p>
            <div className="mt-3.5">
              <button type="button" className="linkish" onClick={handleNotify}>
                申请通知权限（演示）
              </button>
            </div>
          </div>
        </SettingRow>

        <SettingRow label="时区">
          {/* 紧凑原生下拉：候选集固定为常用时区，onChange 即提交，无需草稿与校验态。 */}
          <div className="max-w-sm space-y-2">
            <label
              htmlFor="timezone"
              className="mb-[9px] block font-mono text-[9px] tracking-[0.18em] text-ink-faint"
            >
              IANA 时区
            </label>
            <select
              id="timezone"
              value={currentTimezone}
              onChange={(event) => handleTimezoneChange(event.target.value)}
              className="cursor-pointer rounded-[2px] border border-rule bg-transparent px-[10px] py-[7px] text-xs text-ink-muted outline-none transition-colors focus:border-ink-faint"
            >
              {timezoneOptions.map((zone) => (
                <option key={zone} value={zone}>
                  {zone}
                </option>
              ))}
            </select>
            <p className="font-mono text-[11px] leading-[1.7] tracking-[0.04em] text-ink-faint">
              按本地日期计算今日到期与连续天数。
            </p>
          </div>
        </SettingRow>

        <SettingRow label="更多">
          <div className="flex flex-wrap items-center gap-x-[18px] gap-y-3">
            <Link to="/stats" className="linkish">
              统计
            </Link>
            {user?.role === "ADMIN" ? (
              <Link to="/admin" search={{ tab: "banks" }} className="linkish">
                管理
              </Link>
            ) : null}
            <button
              type="button"
              className="linkish"
              onClick={() => {
                // 真实检查更新：生产构建注册 SW 后有完整能力；dev/不支持时如实提示。
                void checkForUpdate().then((result) => {
                  if (result === "available") {
                    showToast("有新版本可用，刷新页面即可更新。");
                  } else if (result === "latest") {
                    showToast("已是最新版本。");
                  } else if (result === "failed") {
                    showToast("检查失败，请稍后再试。");
                  } else {
                    showToast("当前环境不支持检查更新（生产构建生效）。");
                  }
                });
              }}
            >
              检查更新
            </button>
            <button type="button" className="linkish" onClick={handleRePlacement}>
              重新定级
            </button>
          </div>
        </SettingRow>

        <SettingRow label="数据与隐私">
          <div>
            <p className="text-[13px] leading-[1.75] text-ink-muted">
              深度批改会把译文发给模型供应商（当前 DeepSeek ·
              中国），仅用于本次批改，不用于训练。开启「仅本地」后译文不会外发。
            </p>
            <label className="mt-3.5 flex cursor-pointer items-start gap-2.5 text-[13px] leading-[1.5] text-ink-muted">
              <input
                type="checkbox"
                checked={localOnly}
                onChange={(event) => {
                  setLocalOnly(event.target.checked);
                  showToast(event.target.checked ? "已切换为仅本地批改" : "已允许深度批改调用模型");
                }}
                className="mt-[3px] accent-vermilion"
              />
              <span>
                <span className="text-ink">仅本地批改</span>
                <span className="mt-1 block text-[11px] leading-[1.65] text-ink-faint">
                  关闭后才会调用模型；首次调用前会再确认一次。
                </span>
                <span className="mt-1 block text-[11px] leading-[1.65] text-vermilion-deep">
                  当前版本全部为本地判分。
                </span>
              </span>
            </label>
          </div>
        </SettingRow>

        <SettingRow label="数据">
          <div>
            <p className="text-[13px] leading-[1.7] text-ink-muted">
              {user
                ? "答题流水保留 90 天；聚合统计长期保留；审计日志 1 年。换设备时先导出。"
                : "答题记录都保存在这台设备上，换设备前先导出。"}
            </p>
            <div className="mt-4 flex flex-wrap items-center gap-x-[18px] gap-y-3">
              <button
                type="button"
                className="linkish"
                disabled={exporting}
                onClick={() => void handleExport()}
              >
                {exporting ? "导出中…" : "导出 JSON"}
              </button>
              {user ? (
                <button type="button" className="linkish" onClick={() => setDeleteOpen(true)}>
                  删除账号
                </button>
              ) : null}
              <button
                type="button"
                className="linkish"
                disabled={clearPending}
                onClick={() => setClearOpen(true)}
              >
                清空本地数据
              </button>
            </div>
            {isMockEnabled() ? (
              <div className="mt-5 border-t border-rule pt-4">
                <p className="text-[13px] leading-[1.7] text-ink-muted">
                  演示模式：重置会清空本机 mock 用户、答题、复习与管理日志，并回到登录页。
                </p>
                <div className="mt-3">
                  <button
                    type="button"
                    className="linkish"
                    disabled={resetDemo.isPending}
                    onClick={() => resetDemo.mutate()}
                  >
                    {resetDemo.isPending ? "重置中…" : "重置演示数据"}
                  </button>
                </div>
                {resetDemo.isError ? (
                  <p role="alert" className="mt-2 text-xs text-vermilion-deep">
                    重置失败：{resetDemo.error.message}
                  </p>
                ) : null}
              </div>
            ) : null}
          </div>
        </SettingRow>

        <SettingRow label="快捷键">
          <table className="w-full border-collapse text-xs text-ink-muted">
            <tbody>
              {SHORTCUTS.map(([action, keys]) => (
                <tr key={action} className="border-b border-rule last:border-b-0">
                  <td className="py-2">{action}</td>
                  <td className="py-2 text-right font-mono text-[11px] text-ink-faint">{keys}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </SettingRow>
      </div>

      <ConfirmDialog
        open={deleteOpen}
        title="删除账号"
        description="软删 30 天可恢复，之后匿名化；答题流水会一起清理。"
        reasons={DELETE_REASONS}
        confirmLabel="删除账号"
        onCancel={() => setDeleteOpen(false)}
        onConfirm={() => {
          setDeleteOpen(false);
          // 后端尚无删除端点：如实告知未执行，不伪造成功。
          showToast("需要服务端支持，当前为演示，未执行删除");
        }}
      />

      <ConfirmDialog
        open={clearOpen}
        title="清空本地数据"
        description="将清空本机的答题记录、复习进度、题库缓存与定级结果，设备号保留。此操作不可恢复。"
        confirmLabel="清空"
        onCancel={() => setClearOpen(false)}
        onConfirm={handleClearConfirm}
      />

      {toastMessage ? (
        <p
          role="status"
          className="fixed bottom-8 left-1/2 z-[60] max-w-[92vw] -translate-x-1/2 rounded-[2px] bg-ink px-4 py-[9px] text-center text-[12.5px] text-paper"
        >
          {toastMessage}
        </p>
      ) : null}
    </div>
  );
}
