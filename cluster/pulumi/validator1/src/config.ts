import { z } from 'zod';

export const Validator1ConfigSchema = z.object({
  kms: z
    .object({
      type: z.string(),
      locationId: z.string(),
      projectId: z.string(),
      keyRingId: z.string(),
    })
    .optional(),
});

export type Validator1Config = z.infer<typeof Validator1ConfigSchema>;
