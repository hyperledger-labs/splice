import { z } from 'zod';

const serviceSchema = z.object({
  grpcUrl: z.string().url(),
});

export { serviceSchema };
