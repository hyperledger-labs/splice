import { z } from 'zod';

export const listUsersResponse = z.array(
  z.object({
    user_id: z.string(),
    email: z.string(),
  }),
);

export type ListUsersReponse = z.infer<typeof listUsersResponse>;
