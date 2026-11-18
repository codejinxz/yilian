import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation, useNavigate } from "@tanstack/react-router";
import { type FormEvent, useCallback, useEffect, useId, useRef, useState } from "react";
import { z } from "zod";
import { FieldError, Notice } from "@/components/feedback";
import { useSessionStore } from "@/features/auth/session";
import { api, type CaptchaChallenge } from "@/lib/api";
import { syncGuestAttemptsInBackground } from "@/lib/api/guest-upgrade";
import { ApiError } from "@/lib/api-error";
import { isMockEnabled } from "@/lib/mock-flag";
import { queryKeys } from "@/lib/query";
import {
  loginTotpRequestSchema,
  registerRequestSchema,
  totpChallengeSchema,
} from "@/lib/schemas/account";
import { cn } from "@/lib/utils";

const loginSchema = z.object({
  username: z.string().trim().min(1, "请输入用户名或邮箱").max(64, "用户名最多 64 个字符"),
  password: z.string().min(1, "请输入密码").max(72, "密码最多 72 个字符"),
});

/** 认证页的两种形态：同一页面、同一表单状态，页签在 /login 与 /register 两条路由间切换。 */
export type AuthPageMode = "login" | "register";

/** 提交载荷；kind 区分登录与注册，字段与对应 API 严格对齐。 */
type SubmitInput =
  | {
      kind: "login";
      username: string;
      password: string;
      captchaId?: string;
      captchaCode?: string;
    }
  | {
      kind: "register";
      email: string;
      username?: string;
      password: string;
      inviteCode: string;
      captchaId?: string;
      captchaCode?: string;
    };

/** 表单字段；登录与注册共用一份状态，密码跨页签保留（与原型一致）。 */
interface AuthFormState {
  loginName: string;
  email: string;
  username: string;
  password: string;
  confirm: string;
  inviteCode: string;
  captchaCode: string;
}

const INITIAL_FORM: AuthFormState = {
  loginName: "",
  email: "",
  username: "",
  password: "",
  confirm: "",
  inviteCode: "",
  captchaCode: "",
};

/**
 * history state 的跨路由一次性提示形状（删号成功回执等）：只认
 * `{ flash: 非空字符串 }`，未知来源 state 一律忽略，不渲染任意内容。
 */
const flashStateSchema = z.object({ flash: z.string().min(1) });

/** 从当前 history state 读取一次性提示；形状不合法视为无提示。 */
function readFlashState(state: unknown): string | null {
  const parsed = flashStateSchema.safeParse(state);
  return parsed.success ? parsed.data.flash : null;
}

/** 页签样式；.auth-tabs button：12px、10px 下距、激活朱红下边框压住容器底线。 */
function authTabClass(active: boolean): string {
  return cn(
    "-mb-px border-b pb-[10px] text-xs tracking-[0.06em]",
    active ? "border-vermilion text-ink" : "border-transparent text-ink-faint",
  );
}

/** linkish 文字按钮；.linkish：12px 朱红文字 + 1px 短下划线。 */
function linkishClass(): string {
  return "cursor-pointer border-b border-vermilion/35 bg-transparent text-xs text-vermilion disabled:cursor-not-allowed disabled:opacity-40";
}

/** 字段输入框；.field input：透明底、无框、1px 底线、8px 0 内距、15px、朱红聚焦。 */
function fieldInputClass(): string {
  return "w-full border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition-colors placeholder:text-ink-faint focus:border-vermilion disabled:cursor-not-allowed disabled:opacity-60";
}

/** 字段容器与 label；.field / .field label：9px mono 大字距边注。 */
function fieldLabelClass(): string {
  return "mb-[9px] block font-mono text-[9px] tracking-[0.18em] text-ink-faint";
}

/**
 * 认证页：登录与邀请码注册共用一套表单（register 初态由 /register 路由传入）。
 * 页签用类型化导航在两条公开路由间切换，两条路由都保留；游客可直接以游客身份进入今日。
 * 渲染在壳层内（顶栏/页脚view-login），本组件只负责 360px 窄栏内容。
 * 保留原有真实链路：zod 校验、CSRF、72 字节密码检查、演示账号提示与错误展示。
 * @param props.initialMode 初态模式；/login 渲染登录，/register 渲染注册
 */
export function LoginPage({ initialMode = "login" }: { initialMode?: AuthPageMode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const setAuthenticated = useSessionStore((state) => state.setAuthenticated);
  const mode = initialMode;
  // 一次性跨路由提示（如删号成功回执）：挂载时从 history state 取入组件态并立即
  // 清掉路由 state——刷新或后退不会重复弹出；提示在组件态里持续可见到用户离开本页。
  const [flash] = useState<string | null>(() => readFlashState(location.state));
  const usernameId = useId();
  const emailId = useId();
  const registerUsernameId = useId();
  const passwordId = useId();
  const confirmId = useId();
  const inviteId = useId();
  const captchaFieldId = useId();
  const errorRef = useRef<HTMLParagraphElement>(null);

  const [form, setForm] = useState<AuthFormState>(INITIAL_FORM);
  const [captcha, setCaptcha] = useState<CaptchaChallenge | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);
  // 两步验证第二步：challengeToken 只存组件内存态（不入 storage），刷新页面即回第一步。
  const [totpChallengeToken, setTotpChallengeToken] = useState<string | null>(null);
  const [totpCode, setTotpCode] = useState("");
  const [totpUseRecovery, setTotpUseRecovery] = useState(false);
  const totpCodeId = useId();

  // useCallback 固定引用，供挂载 effect 与提交失败刷新共用；api 为模块级依赖，依赖数组为空。
  const loadCaptcha = useCallback(() => {
    api
      .getCaptcha()
      .then(setCaptcha)
      .catch(() => setCaptcha(null));
  }, []);

  useEffect(() => {
    loadCaptcha();
  }, [loadCaptcha]);

  // 提示取入组件态后清掉 history state：replace 同路径不产生新历史项，刷新/后退不重放；
  // pathname 是字符串依赖，replace 后 location 对象虽变化但值不变，effect 不会重跑。
  useEffect(() => {
    if (flash) {
      void navigate({ to: location.pathname, replace: true, state: {} });
    }
  }, [flash, navigate, location.pathname]);

  const mutation = useMutation({
    mutationFn: (input: SubmitInput) =>
      input.kind === "login"
        ? api.login({
            username: input.username,
            password: input.password,
            captchaId: input.captchaId,
            captchaCode: input.captchaCode,
          })
        : api.register({
            email: input.email,
            username: input.username,
            password: input.password,
            inviteCode: input.inviteCode,
            captchaId: input.captchaId,
            captchaCode: input.captchaCode,
          }),
    onSuccess: (auth) => {
      setAuthenticated();
      queryClient.setQueryData(queryKeys.me, auth.user);
      // 游客本机记录迁移：后台执行不阻塞跳转；失败保留本地数据，
      // 由设置页的「同步本地记录到账号」入口与欠账提示兜底。
      void syncGuestAttemptsInBackground();
      void navigate({ to: "/today" });
    },
    onError: (error) => {
      // 已启用两步验证的账号：密码步返回 40305，data 携 challengeToken——切到第二步，
      // 密码本身已验证，不要把本步失败当「凭据错误」提示。
      if (error instanceof ApiError && error.code === 40305) {
        const challenge = totpChallengeSchema.safeParse(error.data);
        if (challenge.success) {
          // 进入第二步前清掉第一步的 mutation error：errorMessage 链会回退到
          // mutation.error（40305 文案），让「错误聚焦」效果在第二步输入时反复抢焦点。
          mutation.reset();
          setTotpChallengeToken(challenge.data.challengeToken);
          setFieldError(null);
          setForm((current) => ({ ...current, captchaCode: "" }));
          return;
        }
      }
      // 失败（含验证码错误）后旧挑战已消费，重取图形码并清空输入。
      setForm((current) => ({ ...current, captchaCode: "" }));
      loadCaptcha();
    },
  });

  // 两步验证第二步：challengeToken + 8 位验证码（TOTP 或恢复码），成功即登录完成。
  const totpMutation = useMutation({
    mutationFn: (input: { challengeToken: string; code: string }) => api.loginTotp(input),
    onSuccess: (auth) => {
      setTotpChallengeToken(null);
      setTotpCode("");
      setAuthenticated();
      queryClient.setQueryData(queryKeys.me, auth.user);
      void syncGuestAttemptsInBackground();
      void navigate({ to: "/today" });
    },
    onError: (error) => {
      // 挑战过期/失效（40101「重新登录」口径）：第二步失效回第一步重走密码步。
      if (error instanceof ApiError && error.code === 40101 && error.message.includes("重新登录")) {
        setTotpChallengeToken(null);
        setTotpCode("");
      }
      setTotpCode("");
    },
  });

  // 演示模式快捷入口：以管理员登录后直达题库管理，真实走一遍登录链路。
  const demoAdminMutation = useMutation({
    mutationFn: () => api.login({ username: "admin", password: "change-me-now" }),
    onSuccess: (auth) => {
      setAuthenticated();
      queryClient.setQueryData(queryKeys.me, auth.user);
      void navigate({ to: "/admin", search: { tab: "banks" } });
    },
  });

  const errorMessage = fieldError ?? totpMutation.error?.message ?? mutation.error?.message ?? null;

  useEffect(() => {
    if (errorMessage) {
      errorRef.current?.focus();
    }
  }, [errorMessage]);

  // /login 与 /register 渲染的是不同路由组件，切换页签必然重挂载，表单与错误状态天然隔离；
  // 这里不需要再做跨模式的清理。

  /**
   * 第二步提交：challengeToken + 8 位验证码；zod 校验通过后交两步验证 mutation。
   * @param event 表单提交事件
   */
  function handleTotpSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!totpChallengeToken) {
      return;
    }
    const parsed = loginTotpRequestSchema.safeParse({
      challengeToken: totpChallengeToken,
      code: totpCode,
    });
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message ?? "请检查输入");
      return;
    }
    setFieldError(null);
    totpMutation.mutate(parsed.data);
  }

  /**
   * 回到第一步：清掉挑战与第二步输入（挑战过期或用户主动放弃时用）。
   */
  function resetTotpStep() {
    setTotpChallengeToken(null);
    setTotpCode("");
    setTotpUseRecovery(false);
    setFieldError(null);
    totpMutation.reset();
    mutation.reset();
  }

  /**
   * 提交前按模式做客户端校验；密码额外按 UTF-8 字节数检查，因为后端 BCrypt 限的是字节而非字符。
   * @param event 表单提交事件
   */
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mode === "login") {
      const parsed = loginSchema.safeParse({ username: form.loginName, password: form.password });
      if (!parsed.success) {
        setFieldError(parsed.error.issues[0]?.message ?? "请检查输入");
        return;
      }
      if (new TextEncoder().encode(parsed.data.password).length > 72) {
        setFieldError("密码过长（最多 72 字节）");
        return;
      }
      if (captcha?.enabled && !form.captchaCode.trim()) {
        setFieldError("请输入图形验证码");
        return;
      }
      setFieldError(null);
      mutation.mutate({
        kind: "login",
        username: parsed.data.username,
        password: parsed.data.password,
        captchaId: captcha?.enabled ? captcha.captchaId : undefined,
        captchaCode: captcha?.enabled ? form.captchaCode : undefined,
      });
      return;
    }

    if (form.password !== form.confirm) {
      setFieldError("两次输入的密码不一致");
      return;
    }
    if (new TextEncoder().encode(form.password).length > 72) {
      setFieldError("密码过长（最多 72 字节）");
      return;
    }
    const parsed = registerRequestSchema.safeParse({
      email: form.email,
      username: form.username,
      password: form.password,
      inviteCode: form.inviteCode,
    });
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message ?? "请检查输入");
      return;
    }
    if (
      parsed.data.password.toLowerCase() === parsed.data.email.toLowerCase() ||
      (parsed.data.username &&
        parsed.data.password.toLowerCase() === parsed.data.username.toLowerCase())
    ) {
      setFieldError("密码不能等于用户名或邮箱");
      return;
    }
    if (captcha?.enabled && !form.captchaCode.trim()) {
      setFieldError("请输入图形验证码");
      return;
    }
    setFieldError(null);
    mutation.mutate({
      kind: "register",
      email: parsed.data.email,
      username: parsed.data.username,
      password: parsed.data.password,
      inviteCode: parsed.data.inviteCode,
      captchaId: captcha?.enabled ? captcha.captchaId : undefined,
      captchaCode: captcha?.enabled ? form.captchaCode : undefined,
    });
  }

  /**
   * 更新字段并清掉过期错误，避免提示落后于当前输入。
   * @param field 字段名
   * @param value 新值
   */
  function updateField(field: keyof AuthFormState, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
    if (fieldError) {
      setFieldError(null);
    }
    if (mutation.error) {
      mutation.reset();
    }
  }

  return (
    // view-login 的 .auth：360px 窄栏居中、顶部 4vh 起始，不垂直居中、无卡片；
    // 壳层 .page 已有 40px 页边距，这里不再加水平内距，360px 才是真实栏宽。
    <div className="mx-auto w-full max-w-[360px] pt-[4vh]">
      <div className="rise">
        <h1 className="font-serif text-[32px] font-medium text-ink">
          {mode === "login" ? "登录" : "注册"}
        </h1>
        {/* 话术对齐真实数据流：登录/注册成功会在后台把本机游客答题记录自动并入账号
            （原始记录不删，失败可在设置页导出/重试）；复习排程按账号侧队列计算、不随
            记录迁移——这正是「刚注册账号复习为 0」观感的真相，先说清避免二次困惑。 */}
        <p className="mb-[30px] mt-[10px] text-[13px] leading-[1.75] text-ink-muted">
          登录或注册后，本机的练习记录会自动并入账号（原始记录仍保留在本机，设置页可导出或重试）；复习安排不随记录迁移，会按之后的练习重新排。不登录也能练，数据留在这台设备。
        </p>

        {/* 跨路由一次性提示（删号成功回执）：来源页 toast 随导航卸载即丢失（D-1），
            由 history state 携带至此渲染；state 已在挂载时清除，刷新/后退不重放。 */}
        {flash ? (
          <Notice tone="info" className="mb-[30px]">
            {flash}
          </Notice>
        ) : null}

        {totpChallengeToken ? (
          <p className="mb-[30px] mt-[10px] text-[13px] leading-[1.75] text-ink-muted">
            密码已验证。请输入验证器 App 生成的 8 位动态码；验证器丢失时可用启用时抄写的恢复码登录。
          </p>
        ) : (
          <>
            <div className="mb-[26px] flex gap-[22px] border-b border-rule">
              <Link to="/login" className={authTabClass(mode === "login")}>
                登录
              </Link>
              <Link to="/register" className={authTabClass(mode === "register")}>
                注册
              </Link>
            </div>

            {/* mock 演示提示：.import-hint（11px mono 大字距边注），放在页签与字段之间 */}
            {isMockEnabled() ? (
              <div className="mb-5 space-y-1.5">
                {mode === "register" ? (
                  <p className="font-mono text-[11px] tracking-[0.06em] text-ink-faint">
                    演示邀请码：YL-DEMO-2026（一码一人）
                  </p>
                ) : null}
                <p className="font-mono text-[11px] tracking-[0.06em] text-ink-faint">
                  演示账号：admin / change-me-now · learner / learner123
                </p>
              </div>
            ) : null}
          </>
        )}

        <form onSubmit={totpChallengeToken ? handleTotpSubmit : handleSubmit} noValidate>
          {totpChallengeToken ? (
            <div className="mb-5">
              <label htmlFor={totpCodeId} className={fieldLabelClass()}>
                {totpUseRecovery ? "恢复码" : "动态验证码"}
              </label>
              <input
                id={totpCodeId}
                name="totpCode"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={8}
                required
                // biome-ignore lint/a11y/noAutofocus: 进入第二步即聚焦验证码输入，与首步交互一致
                autoFocus
                aria-invalid={Boolean(errorMessage)}
                aria-describedby={errorMessage ? "auth-error" : undefined}
                value={totpCode}
                onChange={(event) => {
                  setTotpCode(event.target.value);
                  if (fieldError) {
                    setFieldError(null);
                  }
                  if (totpMutation.error) {
                    totpMutation.reset();
                  }
                }}
                placeholder="8 位数字"
                className={fieldInputClass()}
              />
              <div className="mt-3">
                <button
                  type="button"
                  className={linkishClass()}
                  onClick={() => {
                    setTotpUseRecovery((current) => !current);
                    setTotpCode("");
                    setFieldError(null);
                  }}
                >
                  {totpUseRecovery ? "改用验证器动态码" : "验证器丢失？改用恢复码"}
                </button>
              </div>
            </div>
          ) : (
            <>
              {mode === "login" ? (
                <div className="mb-5">
                  <label htmlFor={usernameId} className={fieldLabelClass()}>
                    用户名或邮箱
                  </label>
                  <input
                    id={usernameId}
                    name="username"
                    autoComplete="username"
                    autoCapitalize="none"
                    spellCheck={false}
                    required
                    // biome-ignore lint/a11y/noAutofocus: 进入登录页即聚焦首个输入，是的刻意交互
                    autoFocus
                    aria-invalid={Boolean(errorMessage)}
                    aria-describedby={errorMessage ? "auth-error" : undefined}
                    value={form.loginName}
                    onChange={(event) => updateField("loginName", event.target.value)}
                    placeholder="例如 admin"
                    className={fieldInputClass()}
                  />
                </div>
              ) : (
                <>
                  <div className="mb-5">
                    <label htmlFor={emailId} className={fieldLabelClass()}>
                      邮箱
                    </label>
                    <input
                      id={emailId}
                      name="email"
                      type="email"
                      autoComplete="email"
                      autoCapitalize="none"
                      spellCheck={false}
                      required
                      // biome-ignore lint/a11y/noAutofocus: 注册页同样聚焦首个输入，与登录页交互一致
                      autoFocus
                      value={form.email}
                      onChange={(event) => updateField("email", event.target.value)}
                      placeholder="you@example.com"
                      className={fieldInputClass()}
                    />
                  </div>

                  <div className="mb-5">
                    <label htmlFor={registerUsernameId} className={fieldLabelClass()}>
                      用户名（可选）
                    </label>
                    <input
                      id={registerUsernameId}
                      name="username"
                      autoComplete="username"
                      autoCapitalize="none"
                      spellCheck={false}
                      value={form.username}
                      onChange={(event) => updateField("username", event.target.value)}
                      placeholder="留空则由邮箱派生"
                      className={fieldInputClass()}
                    />
                  </div>
                </>
              )}

              <div className="mb-5">
                <label htmlFor={passwordId} className={fieldLabelClass()}>
                  密码
                </label>
                <input
                  id={passwordId}
                  name="password"
                  type="password"
                  autoComplete={mode === "login" ? "current-password" : "new-password"}
                  required
                  aria-invalid={Boolean(errorMessage)}
                  aria-describedby={errorMessage ? "auth-error" : undefined}
                  value={form.password}
                  onChange={(event) => updateField("password", event.target.value)}
                  placeholder={mode === "login" ? "••••••••" : "至少 8 位"}
                  className={fieldInputClass()}
                />
              </div>

              {captcha?.enabled ? (
                <div className="mb-5">
                  <label htmlFor={captchaFieldId} className={fieldLabelClass()}>
                    图形验证码
                  </label>
                  <div className="flex items-center gap-3">
                    <input
                      id={captchaFieldId}
                      name="captchaCode"
                      autoComplete="off"
                      maxLength={8}
                      aria-invalid={Boolean(errorMessage)}
                      aria-describedby={errorMessage ? "auth-error" : undefined}
                      value={form.captchaCode}
                      onChange={(event) => updateField("captchaCode", event.target.value)}
                      placeholder="输入图中字符"
                      className={fieldInputClass()}
                    />
                    <button
                      type="button"
                      onClick={loadCaptcha}
                      aria-label="刷新图形验证码"
                      className="shrink-0"
                    >
                      <img
                        src={`data:image/png;base64,${captcha.imageBase64}`}
                        alt="图形验证码，点击刷新"
                        className="h-10 rounded border border-rule"
                      />
                    </button>
                  </div>
                </div>
              ) : null}

              {mode === "register" ? (
                <>
                  <div className="mb-5">
                    <label htmlFor={confirmId} className={fieldLabelClass()}>
                      确认密码
                    </label>
                    <input
                      id={confirmId}
                      name="confirm"
                      type="password"
                      autoComplete="new-password"
                      required
                      value={form.confirm}
                      onChange={(event) => updateField("confirm", event.target.value)}
                      placeholder="再输入一次"
                      className={fieldInputClass()}
                    />
                  </div>

                  <div className="mb-5">
                    <label htmlFor={inviteId} className={fieldLabelClass()}>
                      邀请码
                    </label>
                    <input
                      id={inviteId}
                      name="inviteCode"
                      autoComplete="off"
                      required
                      value={form.inviteCode}
                      onChange={(event) => updateField("inviteCode", event.target.value)}
                      placeholder="YL-XXXX-XXXX"
                      className={fieldInputClass()}
                    />
                  </div>
                </>
              ) : null}
            </>
          )}

          <FieldError>{fieldError}</FieldError>

          {/* 服务端错误展示：第一步与两步验证第二步共用同一段落；客户端校验错误走 FieldError，
              两者同现时以 FieldError 为准，不重复展示。 */}
          {(mutation.error || totpMutation.error) && !fieldError ? (
            <p
              id="auth-error"
              ref={errorRef}
              role="alert"
              tabIndex={-1}
              className="mt-3 text-xs leading-5 text-vermilion-deep"
            >
              {errorMessage}
            </p>
          ) : null}

          {/* .auth .btn：全宽、8px 上距、.btn.primary 形态（13px、11px 22px、2px 圆角） */}
          <button
            type="submit"
            disabled={
              totpChallengeToken
                ? totpMutation.isPending
                : mutation.isPending || demoAdminMutation.isPending
            }
            aria-busy={totpChallengeToken ? totpMutation.isPending : mutation.isPending}
            className="mt-2 w-full cursor-pointer rounded-[2px] border border-vermilion bg-vermilion px-[22px] py-[11px] text-[13px] leading-[1.2] tracking-[0.06em] text-white transition-colors duration-200 hover:border-vermilion-deep hover:bg-vermilion-deep disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-vermilion disabled:hover:bg-vermilion"
          >
            {totpChallengeToken
              ? totpMutation.isPending
                ? "验证中…"
                : "验证并登录"
              : mutation.isPending
                ? mode === "login"
                  ? "登录中…"
                  : "注册中…"
                : mode === "login"
                  ? "登录"
                  : "注册并开始"}
          </button>
        </form>

        {/* .alt：24px 上距、12px、居中、10px 间距；第二步模式下只给「回第一步」出口 */}
        <div className="mt-6 flex flex-wrap items-center justify-center gap-2.5 text-xs text-ink-faint">
          {totpChallengeToken ? (
            <button type="button" className={linkishClass()} onClick={resetTotpStep}>
              返回重新登录
            </button>
          ) : (
            <>
              <button
                type="button"
                className={linkishClass()}
                onClick={() => void navigate({ to: "/today" })}
              >
                以游客身份继续
              </button>
              {isMockEnabled() ? (
                <>
                  <span aria-hidden="true">·</span>
                  <button
                    type="button"
                    className={linkishClass()}
                    onClick={() => demoAdminMutation.mutate()}
                    disabled={demoAdminMutation.isPending}
                    aria-busy={demoAdminMutation.isPending}
                  >
                    {demoAdminMutation.isPending ? "进入演示…" : "以管理员身份体验（演示）"}
                  </button>
                </>
              ) : null}
            </>
          )}
        </div>

        {demoAdminMutation.error ? (
          <p role="alert" className="mt-3 text-center text-xs leading-5 text-vermilion-deep">
            {demoAdminMutation.error.message}
          </p>
        ) : null}
      </div>
    </div>
  );
}

/**
 * 注册入口：与登录页共用同一套表单，仅初态为注册；独立路由只为保留旧链接与既有测试不断。
 * 表单逻辑全部在 LoginPage 内维护，这里不再单独实现。
 */
export function RegisterPage() {
  return <LoginPage initialMode="register" />;
}
