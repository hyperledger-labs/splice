import { z } from 'zod';

const serviceSchema = z.object({
  url: z.string().url(),
});

export { serviceSchema };
