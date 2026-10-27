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
});

export type AuthResponse = z.infer<typeof authResponseSchema>;

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
