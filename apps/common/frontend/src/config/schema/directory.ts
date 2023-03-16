import { z } from 'zod';

const directorySchema = z.object({
  jsonApiUrl: z.string().url(),
  url: z.string().url(),
});

export { directorySchema };
