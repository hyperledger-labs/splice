// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ValidatorNodeConfigSchema } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { clusterSubConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { z } from 'zod';

const ValidatorStableOldConfigSchema = z
  .object({
    deduplicationDuration: z.string().optional(),
  })
  .and(ValidatorNodeConfigSchema);

export type ValidatorStableOldConfig = z.infer<typeof ValidatorStableOldConfigSchema>;

export const validatorStableOldConfig: ValidatorStableOldConfig =
  ValidatorStableOldConfigSchema.parse(clusterSubConfig('validator-stable-old'));
