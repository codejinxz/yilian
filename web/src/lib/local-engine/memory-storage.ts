/**
 * 引擎存储的内存实现：专供单元测试（jsdom 没有 IndexedDB，也不为此引入 fake-indexeddb）。
 * 行为与 IndexedDB 实现保持一致：put 覆盖同键、get 缺失返回 null、getAll 无固定顺序保证。
 */
import type {
  CachedBank,
  EngineKeyValueStore,
  EngineRecordStore,
  EngineStorage,
  StoredAttempt,
  StoredDispute,
  StoredReview,
} from "./types";

/**
 * 构造基于 Map 的同步键值存储。
 * @param seed 初始键值；省略为空
 * @returns 键值存储实例
 */
function createMemoryKv(seed?: Map<string, string>): EngineKeyValueStore {
  const map = seed ?? new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? (map.get(key) as string) : null),
    setItem: (key, value) => {
      map.set(key, value);
    },
    removeItem: (key) => {
      map.delete(key);
    },
  };
}

/**
 * 构造基于 Map 的异步对象存储。
 * @param seed 初始条目；省略为空
 * @returns 对象存储实例
 */
function createMemoryRecordStore<T>(seed?: Map<string, T>): EngineRecordStore<T> {
  const map = seed ?? new Map<string, T>();
  return {
    get: async (key) => (map.has(key) ? (map.get(key) as T) : null),
    put: async (key, value) => {
      map.set(key, value);
    },
    remove: async (key) => {
      map.delete(key);
    },
    getAll: async () => [...map.values()],
    clear: async () => {
      map.clear();
    },
  };
}

/**
 * 构造完整的内存存储后端；每个测试用例调用一次，保证用例之间互不污染。
 * 各 store 只创建一次并复用，同一后端内的写入互相可见（与 IndexedDB 行为一致）。
 * @returns 引擎存储后端
 */
export function createMemoryEngineStorage(): EngineStorage {
  const attempts = createMemoryRecordStore<StoredAttempt>(new Map<string, StoredAttempt>());
  const reviews = createMemoryRecordStore<StoredReview>(new Map<string, StoredReview>());
  const disputes = createMemoryRecordStore<StoredDispute>(new Map<string, StoredDispute>());
  const bankCache = createMemoryRecordStore<CachedBank>(new Map<string, CachedBank>());
  return {
    kv: createMemoryKv(),
    attempts: () => Promise.resolve(attempts),
    reviews: () => Promise.resolve(reviews),
    disputes: () => Promise.resolve(disputes),
    bankCache: () => Promise.resolve(bankCache),
  };
}
