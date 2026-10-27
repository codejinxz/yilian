/**
 * 译练 Service Worker（手写、零依赖）。
 *
 * 缓存策略（对齐契约「本地优先、失败可降级」）：
 * - 只拦截「同源 GET 且路径在 /assets/ 下」的构建产物：Vite 产物文件名带内容 hash，
 *   文件名即版本，cache-first 不会拿到过期代码；未命中回源后写入缓存。
 * - 导航请求（HTML）与 /api/ 一律不拦截，等同 network-only：
 *   答题数据在 IndexedDB（本地引擎），接口响应时效性强，缓存会制造「看似成功实则过期」
 *   的假象；离线兜底由页面自身的离线横幅与本地引擎承担，而不是 SW 伪造响应。
 * - 未命中缓存且网络失败时直接抛错，交还原生的加载失败提示，不伪装成功。
 *
 * 版本与更新：
 * - 缓存名带版本号；activate 时删除旧版本缓存，所有缓存均可重建（契约 §3.8）。
 * - 红线：绝不自动 skipWaiting。新 SW 安装完成后停在 waiting 状态，
 *   只有页面发来 { type: "SKIP_WAITING" } 消息（用户点了「刷新」）才生效，
 *   保证「更新用户可见、用户确认后生效」。
 */

/** 当前静态缓存版本；发布新前端且需强制清缓存时递增数字。 */
const CACHE_NAME = "yilian-static-v1";

/**
 * 判断请求是否属于「可安全 cache-first」的静态资源：同源、GET、且在 /assets/ 下。
 * /assets/ 之外的文件名不带内容 hash，cache-first 有过期风险，一律不缓存。
 * @param {Request} request 待判定的请求
 * @returns {boolean} 是否可缓存
 */
function isHashedStaticRequest(request) {
  if (request.method !== "GET") {
    return false;
  }
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) {
    return false;
  }
  return url.pathname.startsWith("/assets/");
}

/**
 * cache-first：命中直接返回；未命中回源并把成功响应写入缓存。
 * 只缓存 2xx，避免把错误页固化进缓存。
 * @param {Request} request 资源请求
 * @returns {Promise<Response>} 缓存或网络响应；两者皆失败时抛出网络错误
 */
async function cacheFirst(request) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(request);
  if (cached) {
    return cached;
  }
  const response = await fetch(request);
  if (response.ok) {
    // 必须克隆：响应体是流，交给页面后原对象不能再读。
    void cache.put(request, response.clone());
  }
  return response;
}

self.addEventListener("install", () => {
  // 刻意留空：不预缓存应用壳（导航请求按约定走网络），更不在此调用 skipWaiting——
  // 生效时机只能由页面消息触发（见 message 监听），这是不可协商的红线。
});

self.addEventListener("activate", (event) => {
  // 清理旧版本缓存；所有缓存可由网络重建，删除不会丢业务数据。
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(
        names
          .filter((name) => name.startsWith("yilian-static-") && name !== CACHE_NAME)
          .map((name) => caches.delete(name)),
      );
    })(),
  );
});

self.addEventListener("fetch", (event) => {
  // 只接管静态资源；导航请求与 /api/ 不调用 respondWith，浏览器默认走网络。
  if (!isHashedStaticRequest(event.request)) {
    return;
  }
  event.respondWith(cacheFirst(event.request));
});

self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "SKIP_WAITING") {
    // 全文件唯一允许调用 skipWaiting 的入口，且必须来自页面的显式消息（用户确认刷新）。
    self.skipWaiting();
  }
});
