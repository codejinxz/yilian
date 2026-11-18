import { z } from "zod";
import { idSchema, userRoleSchema } from "./common";

/**
 * 当前用户。`id` 按契约是字符串，同时兼容旧服务端的 number；`passwordChanged`
 * 在旧响应中可能缺失，默认 false 只用于避免整页解析失败。
 */
export const currentUserSchema = z.object({
  id: idSchema,
  username: z.string().min(1),
  role: userRoleSchema,
  passwordChanged: z.boolean().optional().default(false),
  // TODO(mock): 演示数据，真实接口落地后删除——mock 会话补充邮箱与答题数，供壳层展示。
  email: z.string().nullable().optional(),
  attemptsCount: z.number().int().optional(),
});

export type CurrentUser = z.infer<typeof currentUserSchema>;

export const authResponseSchema = z.object({
  accessToken: z.string().min(1),
  tokenType: z.string().min(1),
  expiresIn: z.number().int().positive(),
  user: currentUserSchema,
  // 恢复码登录时后端回显剩余恢复码数；普通登录/刷新为 null 或缺省。
  remainingRecoveryCodes: z.number().int().nonnegative().nullable().optional(),
});

export type AuthResponse = z.infer<typeof authResponseSchema>;

/**
 * 40305「需要两步验证」失败信封的 data 载荷：challengeToken 仅代表「密码已验证」，
 * 不是 access token，5 分钟过期。
 */
export const totpChallengeSchema = z.object({
  challengeToken: z.string().min(1),
  expiresIn: z.number().int().positive(),
});

export type TotpChallenge = z.infer<typeof totpChallengeSchema>;

/** 两步验证状态（GET /auth/2fa/status）；响应不含任何密钥材料。 */
export const totpStatusSchema = z.object({
  enabled: z.boolean(),
  enrolledAt: z.string().min(1).nullable(),
  remainingRecoveryCodes: z.number().int().nonnegative(),
});

export type TotpStatus = z.infer<typeof totpStatusSchema>;

/** 注册第一步回执：Base32 密钥与 otpauth URI 文本仅此一次展示（本批不渲染二维码）。 */
export const totpSetupSchema = z.object({
  secret: z.string().regex(/^[A-Z2-7]{32}$/, "密钥格式异常"),
  otpauthUri: z.string().startsWith("otpauth://totp/"),
});

export type TotpSetup = z.infer<typeof totpSetupSchema>;

/** 注册第二步回执：10 个一次性恢复码明文仅此一次展示。 */
export const totpActivationSchema = z.object({
  recoveryCodes: z.array(z.string().regex(/^\d{8}$/)).length(10),
});

export type TotpActivation = z.infer<typeof totpActivationSchema>;

/** 登录第二步请求：code 同时接受 TOTP 动态码与恢复码。 */
export const loginTotpRequestSchema = z.object({
  challengeToken: z.string().min(1, "登录挑战缺失，请重新登录"),
  code: z
    .string()
    .trim()
    .regex(/^\d{8}$/, "验证码为 8 位数字"),
});

export type LoginTotpRequest = z.infer<typeof loginTotpRequestSchema>;

/** 关闭两步验证请求：密码 + 当前动态码双确认。 */
export const disableTotpRequestSchema = z.object({
  password: z.string().min(1, "请输入当前密码"),
  code: z
    .string()
    .trim()
    .regex(/^\d{8}$/, "验证码为 8 位数字"),
});

export type DisableTotpRequest = z.infer<typeof disableTotpRequestSchema>;

export const registerRequestSchema = z.object({
  email: z.string().trim().email("邮箱格式不正确").max(255, "邮箱过长"),
  username: z
    .union([
      z.literal(""),
      z
        .string()
        .trim()
        .min(3, "用户名至少 3 个字符")
        .max(64, "用户名最多 64 个字符")
        .regex(/^[A-Za-z0-9._-]+$/, "用户名只能包含字母、数字、点、下划线和短横线"),
    ])
    .optional()
    .transform((value) => (value ? value : undefined)),
  password: z.string().min(8, "密码至少 8 个字符").max(72, "密码最多 72 个字符"),
  inviteCode: z.string().trim().min(1, "请填写邀请码").max(64, "邀请码过长"),
});

export type RegisterRequest = z.infer<typeof registerRequestSchema>;

export const settingsSchema = z.object({
  timezone: z.string().min(1),
  dailyGoal: z.union([z.literal(1), z.literal(3), z.literal(5)]),
  ratingStandard: z.enum(["TEM8", "CATTI", "IELTS"]),
  desiredRetention: z.number().min(0.8).max(0.97),
  theme: z.enum(["paper", "night"]),
  notificationsEnabled: z.boolean(),
});

export type UserSettings = z.infer<typeof settingsSchema>;

export const updateSettingsRequestSchema = settingsSchema.partial();

export type UpdateSettingsRequest = z.infer<typeof updateSettingsRequestSchema>;

/** 036 自助删号请求：密码必填；开启两步验证的用户另附 8 位动态码或恢复码。 */
export const deleteAccountRequestSchema = z.object({
  password: z.string().min(1, "请输入当前密码"),
  totpCode: z
    .string()
    .trim()
    .regex(/^\d{8}$/, "验证码为 8 位数字")
    .optional(),
});

export type DeleteAccountRequest = z.infer<typeof deleteAccountRequestSchema>;

/** 036 删号回执：软删完成时刻与匿名化时点（UTC ISO 字符串，相差 30 天冷静期）。 */
export const deleteAccountReceiptSchema = z.object({
  deletedAtUtc: z.string().min(1),
  anonymizeAtUtc: z.string().min(1),
});

export type DeleteAccountReceipt = z.infer<typeof deleteAccountReceiptSchema>;

/**
 * 036 服务端账号数据导出（GET /api/me/export 的裸 JSON，非统一信封）。
 *
 * 顶层块严格校验（账号块/四个切片齐全）；切片行按宽松 record 校验——行字段在服务端
 * 与 DATA-MODEL §5.8–5.11 对齐且前端只整包落盘、不逐行消费，严格 schema 只会把
 * 「服务端加了字段」变成前端下载失败，得不偿失（对照测试锁顶层形状）。
 */
export const accountExportSchema = z.object({
  schemaVersion: z.number().int().positive(),
  exportedAtUtc: z.string().min(1),
  account: z.object({
    id: idSchema,
    username: z.string().min(1),
    email: z.string().nullable(),
    createdAtUtc: z.string().min(1),
    settings: settingsSchema,
  }),
  attempts: z.array(z.record(z.string(), z.unknown())),
  attemptGradings: z.array(z.record(z.string(), z.unknown())),
  appeals: z.array(z.record(z.string(), z.unknown())),
  reviewCards: z.array(z.record(z.string(), z.unknown())),
});

export type AccountExport = z.infer<typeof accountExportSchema>;
