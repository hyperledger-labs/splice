// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ValidatorNodeConfigSchema } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { z } from 'zod';

import { clusterSubConfig } from './config';

export const SplitwellConfigSchema = z
  .object({
    maxDarVersion: z.string().optional(),
  })
  .optional()
  .and(ValidatorNodeConfigSchema);

export type Config = z.infer<typeof SplitwellConfigSchema>;

export const splitwellConfig = SplitwellConfigSchema.parse(clusterSubConfig('splitwell'));
