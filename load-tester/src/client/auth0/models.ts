// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

export const listUsersResponse = z.array(
  z.object({
    user_id: z.string(),
    email: z.string(),
  }),
);

export type ListUsersReponse = z.infer<typeof listUsersResponse>;
