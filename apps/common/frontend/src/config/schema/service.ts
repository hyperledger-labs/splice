import { z } from 'zod';

export const serviceSchema = z.object({
  url: z.string().url(),
});

export const walletSchema = z.object({
  uiUrl: z.string().url(),
});
