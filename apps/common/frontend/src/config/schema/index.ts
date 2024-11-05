// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { authSchema, testAuthSchema } from './auth';
import { pollIntervalSchema } from './polling';
import { serviceSchema } from './service';

export const baseConfigSchema = z.object({
  auth: authSchema,
  pollInterval: pollIntervalSchema,
  testAuth: testAuthSchema,
  services: z.object({}).catchall(serviceSchema),
});

export * from './auth';
export * from './service';
export * from './polling';
