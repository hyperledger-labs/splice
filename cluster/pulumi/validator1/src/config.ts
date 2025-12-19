// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ValidatorNodeConfigSchema } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { clusterSubConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { z } from 'zod';

const Validator1ConfigSchema = z
  .object({
    deduplicationDuration: z.string().optional(),
  })
  .and(ValidatorNodeConfigSchema);

export type Validator1Config = z.infer<typeof Validator1ConfigSchema>;

export const validator1Config: Validator1Config = Validator1ConfigSchema.parse(
  clusterSubConfig('validator1')
);
