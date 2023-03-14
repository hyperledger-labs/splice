import { z } from 'zod';

import { authSchema, testAuthSchema } from './auth';
import { serviceSchema } from './service';

export const baseConfigSchema = z.object({
  auth: authSchema,
  testAuth: testAuthSchema,
  services: z.object({}).catchall(serviceSchema),
});

export * from './auth';
export * from './service';
