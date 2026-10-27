/**
 * 引擎存储的 IndexedDB 实现（默认后端）。
 *
 * 库名 `yilian-local` v1，object stores：records（按 attemptId）、reviewState（按 sentenceId）、
 * disputes（按 `${attemptId}:${annotationKey}`）、bankCache（按 bankId）。
 * 全部使用显式外键（不用 keyPath），put 时由调用方传主键，语义与内存实现一致。
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

const DB_NAME = "yilian-local";
const DB_VERSION = 1;
const STORE_ATTEMPTS = "records";
const STORE_REVIEWS = "reviewState";
const STORE_DISPUTES = "disputes";
const STORE_BANK_CACHE = "bankCache";

let dbPromise: Promise<IDBDatabase> | null = null;

/**
 * 把 IDBRequest 包装成 Promise；onerror / onblocked 统一转为 rejection。
 * @param request 原始请求
 * @returns 请求结果
 */
function toPromise<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("IndexedDB 请求失败"));
  });
}

/**
 * 打开（并按需升级）本地库；连接在整个引擎生命周期内复用，失败时清除缓存以便下次重试。
 * @returns IndexedDB 连接
 */
function openDb(): Promise<IDBDatabase> {
  if (dbPromise) {
    return dbPromise;
  }
  dbPromise = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      for (const name of [STORE_ATTEMPTS, STORE_REVIEWS, STORE_DISPUTES, STORE_BANK_CACHE]) {
        if (!db.objectStoreNames.contains(name)) {
          db.createObjectStore(name);
        }
      }
    };
    request.onsuccess = () => {
      const db = request.result;
      // 连接被其他标签页升级挤掉时丢弃缓存连接，下一次调用重新打开。
      db.onclose = () => {
        dbPromise = null;
      };
      resolve(db);
    };
    request.onerror = () => {
      dbPromise = null;
      reject(request.error ?? new Error("IndexedDB 打开失败"));
    };
    request.onblocked = () => {
      dbPromise = null;
      reject(new Error("IndexedDB 被其他标签页占用"));
    };
  });
  return dbPromise;
}

/**
 * 事务完成等待器：把 transaction 的 complete/abort/error 归一为 Promise。
 * @param transaction 已建立的事务
 * @returns 完成即 resolve、中止即 reject 的 Promise
 */
function transactionDone(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onabort = () => reject(transaction.error ?? new Error("IndexedDB 事务中止"));
    transaction.onerror = () => reject(transaction.error ?? new Error("IndexedDB 事务失败"));
  });
}

/**
 * 在只读事务里执行一次请求并取回结果。
 * @template R 请求结果类型
 * @param storeName store 名
 * @param operation 返回 IDBRequest 的读取操作
 * @returns 请求结果
 */
async function withReadStore<R>(
  storeName: string,
  operation: (store: IDBObjectStore) => IDBRequest<R>,
): Promise<R> {
  const db = await openDb();
  const transaction = db.transaction(storeName, "readonly");
  const request = operation(transaction.objectStore(storeName));
  const result = await toPromise(request);
  await transactionDone(transaction);
  return result;
}

/**
 * 在读写事务里执行一次写操作并等待事务提交。
 * @param storeName store 名
 * @param operation 写操作；不需要返回请求
 */
async function withWriteStore(
  storeName: string,
  operation: (store: IDBObjectStore) => void,
): Promise<void> {
  const db = await openDb();
  const transaction = db.transaction(storeName, "readwrite");
  operation(transaction.objectStore(storeName));
  await transactionDone(transaction);
}

/**
 * 构造某个 store 的记录存储实现。
 * @param storeName store 名
 * @returns 记录存储实例
 */
function createIdbRecordStore<T>(storeName: string): EngineRecordStore<T> {
  return {
    async get(key) {
      const value = await withReadStore<T | undefined>(storeName, (store) => store.get(key));
      return value === undefined ? null : value;
    },
    async put(key, value) {
      await withWriteStore(storeName, (store) => {
        store.put(value, key);
      });
    },
    async remove(key) {
      await withWriteStore(storeName, (store) => {
        store.delete(key);
      });
    },
    async getAll() {
      const values = await withReadStore<T[]>(storeName, (store) => store.getAll());
      return values ?? [];
    },
    async clear() {
      await withWriteStore(storeName, (store) => {
        store.clear();
      });
    },
  };
}

/** localStorage 的直通实现；隐私模式写入失败时抛错，让上层显式降级而不是静默丢数据。 */
const localStorageKv: EngineKeyValueStore = {
  getItem: (key) => localStorage.getItem(key),
  setItem: (key, value) => {
    localStorage.setItem(key, value);
  },
  removeItem: (key) => {
    localStorage.removeItem(key);
  },
};

/**
 * 构造默认的 IndexedDB 存储后端；indexedDB 不可用时引擎所有函数都会 reject。
 * @returns 引擎存储后端
 */
export function createIndexedDbEngineStorage(): EngineStorage {
  return {
    kv: localStorageKv,
    attempts: () => Promise.resolve(createIdbRecordStore<StoredAttempt>(STORE_ATTEMPTS)),
    reviews: () => Promise.resolve(createIdbRecordStore<StoredReview>(STORE_REVIEWS)),
    disputes: () => Promise.resolve(createIdbRecordStore<StoredDispute>(STORE_DISPUTES)),
    bankCache: () => Promise.resolve(createIdbRecordStore<CachedBank>(STORE_BANK_CACHE)),
  };
}
