import { z } from "zod";
import { apiRequest } from "@/lib/api";
import { parseData } from "./shared";

const statusSchema = z.enum(["UPLOADED", "PROCESSING", "PAUSED", "COMPLETED", "FAILED"]);

const batchSchema = z.object({
  batchId: z.string().optional(),
  id: z.string().optional(),
  originalFilename: z.string(),
  // TODO(mock): 演示数据，真实接口落地后删除——mock 把上传时选的题库回显在批次上。
  bankId: z
    .string()
    .nullish()
    .transform((v) => v ?? null),
  sizeBytes: z.number().int().nonnegative(),
  status: statusSchema,
  totalRows: z
    .number()
    .int()
    .nonnegative()
    .nullish()
    .transform((v) => v ?? 0),
  processedRows: z
    .number()
    .int()
    .nonnegative()
    .nullish()
    .transform((v) => v ?? 0),
  successRows: z
    .number()
    .int()
    .nonnegative()
    .nullish()
    .transform((v) => v ?? 0),
  failedRows: z
    .number()
    .int()
    .nonnegative()
    .nullish()
    .transform((v) => v ?? 0),
  errorMessage: z
    .string()
    .nullish()
    .transform((v) => v ?? null),
  createdAt: z.string(),
});

/** 后端上传批次快照。 */
export type UploadBatch = z.infer<typeof batchSchema> & { batchId: string };

/** 行级失败摘要。 */
export interface UploadRowError {
  lineNumber: number;
  reason: string;
}

function normalizeBatch(value: unknown): UploadBatch {
  const parsed = batchSchema.parse(value);
  const batchId = parsed.batchId ?? parsed.id;
  if (!batchId) {
    throw new Error("上传批次缺少 batchId");
  }
  return { ...parsed, batchId };
}

/**
 * 上传 CSV/JSON 文件；返回 202 的批次 id 与初始状态。
 * @param file 待上传文件
 * @param bankId 导入目标题库；随 FormData 提交，服务端导入切片落地后决定句子归属
 * @param signal 取消信号
 * @returns 批次 id 与状态
 */
export async function uploadImport(
  file: File,
  bankId: string,
  signal?: AbortSignal,
): Promise<{ batchId: string; status: UploadBatch["status"] }> {
  const body = new FormData();
  body.append("file", file);
  // TODO(mock): 服务端导入切片落地后 bankId 参与真实归属；当前 mock 接收并回显在批次数据上。
  body.append("bankId", bankId);
  const data = await apiRequest<unknown>("/admin/uploads", {
    method: "POST",
    body,
    signal,
  });
  const parsed = z
    .object({ batchId: z.string().optional(), id: z.string().optional(), status: statusSchema })
    .parse(data);
  const batchId = parsed.batchId ?? parsed.id;
  if (!batchId) {
    throw new Error("上传响应缺少 batchId");
  }
  return { batchId, status: parsed.status };
}

/**
 * 查询批次状态；用于轮询。
 * @param id 批次 id
 * @param signal 取消信号
 * @returns 批次快照
 */
export async function getImport(id: string, signal?: AbortSignal): Promise<UploadBatch> {
  const data = await apiRequest<unknown>(`/admin/uploads/${encodeURIComponent(id)}`, { signal });
  return normalizeBatch(data);
}

/**
 * 继续暂停/失败的批次。
 * @param id 批次 id
 * @returns 最新批次快照
 */
export async function resumeImport(id: string): Promise<UploadBatch> {
  const data = await apiRequest<unknown>(`/admin/uploads/${encodeURIComponent(id)}/resume`, {
    method: "POST",
  });
  return normalizeBatch(data);
}

/**
 * 查询行级失败摘要。
 * @param id 批次 id
 * @param signal 取消信号
 * @returns 失败行样例（最多后端返回数量）
 */
export async function getImportErrors(id: string, signal?: AbortSignal): Promise<UploadRowError[]> {
  const data = await apiRequest<unknown>(`/admin/uploads/${encodeURIComponent(id)}/errors`, {
    signal,
  });
  return parseData(
    z.object({ errors: z.array(z.object({ lineNumber: z.number(), reason: z.string() })) }),
    data,
  ).errors;
}
