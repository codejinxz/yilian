/**
 * PWA 基础设施：Service Worker 注册、版本检查与更新生效。
 *
 * 为什么不缓存 /api/：答题数据在 IndexedDB（本地引擎），接口响应时效性强，
 * 缓存只会制造「看似成功实则过期」的假象；离线兜底由页面自己的离线横幅承担。
 *
 * 更新契约（红线）：绝不静默 skipWaiting。检测到新版本只把状态抛给 UI 提示，
 * 用户点击「刷新」后 applyUpdate() 才 postMessage 让 waiting 中的 SW 生效，
 * 并在旧 SW 失去控制权时整页重载。
 */

/** 「检查更新」的结果，供设置页 toast 等调用方区分文案。 */
export type PwaCheckResult = "available" | "latest" | "failed" | "unsupported";

/** 更新状态订阅者：参数为「是否有新版本待用户确认生效」。 */
type UpdateListener = (updateAvailable: boolean) => void;

const SW_PATH = "/sw.js";

let registration: ServiceWorkerRegistration | null = null;
let updateAvailable = false;
/** applyUpdate 发出 SKIP_WAITING 后置位；只有置位后的 controllerchange 才触发重载。 */
let applyingUpdate = false;
/** 注册只允许发生一次；重复调用（StrictMode、多组件挂载）复用同一 Promise。 */
let registering: Promise<void> | null = null;

const listeners = new Set<UpdateListener>();

/**
 * 把「有新版本待生效」同步给所有订阅方。
 * @param value 最新状态
 */
function setUpdateAvailable(value: boolean): void {
  if (updateAvailable === value) {
    return;
  }
  updateAvailable = value;
  for (const listener of listeners) {
    listener(updateAvailable);
  }
}

/**
 * 监听注册对象上的更新事件：安装中的新 worker 进入 installed 且页面已有旧 controller，
 * 才判定为「有更新」——首次安装（无 controller）不提示，避免新用户看到假更新。
 * @param reg Service Worker 注册对象
 */
function watchRegistration(reg: ServiceWorkerRegistration): void {
  registration = reg;
  if (reg.waiting && navigator.serviceWorker.controller) {
    setUpdateAvailable(true);
  }
  reg.addEventListener("updatefound", () => {
    const installing = reg.installing;
    if (!installing) {
      return;
    }
    installing.addEventListener("statechange", () => {
      if (installing.state === "installed" && navigator.serviceWorker.controller) {
        setUpdateAvailable(true);
      }
    });
  });
}

/**
 * 注册 Service Worker。仅生产构建生效：dev 与测试环境直接跳过，
 * 避免本地缓存干扰 HMR 与联调；不支持或注册失败时静默降级为普通网页。
 * 注册完成后主动检查一次更新，弥补长时间驻留的 SPA 页签（无导航就不会触发浏览器自检）。
 * @returns 注册完成的 Promise；失败不抛出（PWA 是增强能力，失败不影响主链路）
 */
export function registerServiceWorker(): Promise<void> {
  if (!import.meta.env.PROD) {
    return Promise.resolve();
  }
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) {
    return Promise.resolve();
  }
  registering ??= navigator.serviceWorker
    .register(SW_PATH, { scope: "/" })
    .then((reg) => {
      watchRegistration(reg);
      // 静默查一次更新；失败（如离线）不影响注册本身。
      void checkForUpdate().catch(() => undefined);
    })
    .catch(() => {
      // 注册失败（如 HTTP 缓存拿到坏 sw.js）：回退为普通网页，不打扰用户。
      registering = null;
    });
  return registering;
}

/**
 * 订阅「有新版本可用」状态。立即回调一次当前值，保证订阅方与模块状态一致。
 * @param listener 状态变化回调
 * @returns 取消订阅函数
 */
export function subscribePwaUpdate(listener: UpdateListener): () => void {
  listeners.add(listener);
  listener(updateAvailable);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * 主动检查更新：调用 registration.update() 拉取 sw.js 比对。
 * 若发现新 worker，等它装完（或超时兜底）再返回结论，避免「明明有新版却报最新」。
 * @returns "available" 有新版本待生效；"latest" 已是最新；"failed" 检查失败；"unsupported" 环境不支持或未注册
 */
export async function checkForUpdate(): Promise<PwaCheckResult> {
  if (!registration) {
    return "unsupported";
  }
  try {
    await registration.update();
  } catch {
    return "failed";
  }
  const installing = registration.installing;
  if (installing) {
    // statechange 触发即视为装完（installed / redundant 任一）；10s 兜底防止慢网无限等待。
    await new Promise<void>((resolve) => {
      installing.addEventListener("statechange", () => resolve(), { once: true });
      setTimeout(resolve, 10_000);
    });
  }
  const found = Boolean(registration.waiting) && Boolean(navigator.serviceWorker.controller);
  if (found) {
    setUpdateAvailable(true);
  }
  return found ? "available" : "latest";
}

/**
 * 生效已检测到的新版本：向 waiting 中的 SW 发送 SKIP_WAITING（页面到 SW 的唯一控制通道），
 * 新 SW 接管控制权（controllerchange）后整页重载加载新产物。
 * 没有待生效版本时不做任何事——绝不无差别刷新，防止误丢用户未提交的作答状态。
 *
 * @returns 无；重载由 controllerchange 监听器驱动，函数本身不等待生效完成。
 */
export function applyUpdate(): void {
  if (applyingUpdate) {
    return;
  }
  const waiting = registration?.waiting;
  if (!waiting) {
    return;
  }
  applyingUpdate = true;
  waiting.postMessage({ type: "SKIP_WAITING" });
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (!applyingUpdate) {
      // 非「确认更新」引发的控制权变化（如首次注册后 claim），不触发重载。
      return;
    }
    window.location.reload();
  });
}
