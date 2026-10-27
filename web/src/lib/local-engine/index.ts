/**
 * 本地练习引擎的公共出口。
 *
 * 页面与 api 适配层只允许经由本文件使用引擎；存储（IndexedDB / localStorage）细节一律
 * 藏在 EngineStorage 接口后面，不得直接触碰。导出清单是与并行 Agent 约定的契约：
 * 函数名与语义不可变，实现细节可以在引擎内自由演进。
 */

export {
  clearLocalData,
  exportLocalData,
  getAttemptLocal,
  getCachedSentences,
  getDeviceId,
  getOverloadState,
  getPlacementSentences,
  getReviewQueue,
  getStatsSnapshot,
  getTodaySnapshot,
  getWrongQuestions,
  loadPlacementState,
  pickNext,
  postponeOverdue,
  refreshBankCache,
  resetPlacementState,
  resetReviewState,
  reviewToApi,
  savePlacementState,
  setAnnotationAppeal,
  setAnnotationDispute,
  setDailyGoal,
  setEngineStorageForTests,
  submitLocalAttempt,
} from "./engine";
export { createIndexedDbEngineStorage } from "./indexeddb-storage";
export { createMemoryEngineStorage } from "./memory-storage";
export type {
  CachedBank,
  EngineKeyValueStore,
  EngineRecordStore,
  EngineStorage,
  LocalPlacementState,
  LocalReviewState,
  LocalStatsSnapshot,
  LocalTodaySnapshot,
  LocalWrongQuestionRow,
  StoredAttempt,
  StoredDispute,
  StoredReview,
} from "./types";
