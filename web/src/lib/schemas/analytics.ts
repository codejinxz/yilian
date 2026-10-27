import { z } from "zod";
import { practiceModeSchema } from "./common";

export const accuracySchema = z.object({
  from: z.string(),
  to: z.string(),
  totalAttempts: z.number().int().nonnegative(),
  correctRate: z.number().min(0).max(100),
  averageScore: z.number().min(0).max(100),
  byMode: z.array(
    z.object({
      mode: practiceModeSchema,
      attempts: z.number().int().nonnegative(),
      correctRate: z.number().min(0).max(100),
    }),
  ),
});

export type Accuracy = z.infer<typeof accuracySchema>;

export const errorTagsSchema = z.object({
  from: z.string(),
  to: z.string(),
  items: z.array(
    z.object({
      tag: z.string(),
      count: z.number().int().nonnegative(),
      ratio: z.number().min(0).max(100),
    }),
  ),
});

export type ErrorTags = z.infer<typeof errorTagsSchema>;
