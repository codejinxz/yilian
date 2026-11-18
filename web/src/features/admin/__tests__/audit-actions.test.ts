import { describe, expect, it } from "vitest";
import { ACTION_FILTER_OPTIONS, ACTION_LABELS, actionLabel } from "@/features/admin/constants";

/**
 * 后端 admin_audit_log 的实际动作全集（对照 server 源码核对）：
 * OperationLog 注解各 action（AuthController/MeController/AdminUserController/
 * AdminBankController/AdminSentenceController/AdminGenerationController/
 * ImportBatchController）+ audit-events 白名单（AdminAuditEventServiceImpl）。
 * 下拉若漏掉其中之一，对应动作的日志就只能混在「全部操作」里翻页找。
 */
const BACKEND_ACTION_UNIVERSE: Array<[string, string]> = [
  // account 模块（@OperationLog）
  ["LOGIN", "登录"],
  ["LOGOUT", "退出登录"],
  ["PASSWORD_CHANGE", "修改密码"],
  ["SETTINGS_UPDATE", "更新设置"],
  // admin 模块（@OperationLog）
  ["BANK_CREATE", "新建题库"],
  ["BANK_UPDATE", "更新题库"],
  ["BANK_DELETE", "删除题库"],
  ["SENTENCE_UPDATE", "编辑题目"],
  ["SENTENCE_STATUS", "变更题目状态"],
  ["SENTENCE_BULK_STATUS", "批量变更题目状态"],
  ["SENTENCE_BULK_MOVE", "批量移动题目"],
  ["USER_STATUS_CHANGE", "变更用户状态"],
  ["USER_ROLE_CHANGE", "变更用户角色"],
  ["INVITE_CREATE", "生成邀请码"],
  // AI 出题导入（AdminGenerationController）
  ["GENERATION_IMPORT", "生成导入"],
  // content 模块（@OperationLog）
  ["IMPORT_PREVIEW", "预览导入"],
  ["IMPORT_COMMIT", "提交导入"],
  ["IMPORT_ROLLBACK", "回滚导入"],
  // audit-events 白名单
  ["RESET_PASSWORD", "重置密码"],
  ["EXPORT_USER_DATA", "导出数据"],
  ["SESSION_REVOKE", "吊销会话"],
  ["MODEL_CONFIG_UPDATE", "更新模型配置"],
  ["MODEL_TOGGLE", "启停模型"],
  ["PROMPT_UPDATE", "更新 Prompt"],
];

describe("管理端操作日志动作目录", () => {
  it("AC-4: 动作标签覆盖后端 OperationLog 与 audit-events 白名单的全部动作", () => {
    for (const [action, label] of BACKEND_ACTION_UNIVERSE) {
      expect(ACTION_LABELS[action], `${action} 缺少中文名`).toBe(label);
    }
  });

  it("AC-4: 筛选下拉选项含「全部操作」与后端动作全集（含 GENERATION_IMPORT=生成导入）", () => {
    const values = ACTION_FILTER_OPTIONS.map((option) => option.value);
    expect(values[0]).toBe("all");
    for (const [action] of BACKEND_ACTION_UNIVERSE) {
      expect(values, `${action} 不在下拉选项里`).toContain(action);
    }
    const generation = ACTION_FILTER_OPTIONS.find((option) => option.value === "GENERATION_IMPORT");
    expect(generation?.label).toBe("生成导入");
  });

  it("AC-4: 未知动作码在 actionLabel 原样透出，不吞掉新动作", () => {
    expect(actionLabel("SOME_FUTURE_ACTION")).toBe("SOME_FUTURE_ACTION");
    expect(actionLabel("GENERATION_IMPORT")).toBe("生成导入");
  });
});
