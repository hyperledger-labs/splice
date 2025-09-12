// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import util from 'node:util';
import { z } from 'zod';

export const ValidatorRunbookConfigSchema = z.object({
  validator: z
    .object({
      partyAllocator: z.object({
        enable: z.boolean(),
      }),
    })
    .optional(),
});

export const validatorRunbookConfig =
  ValidatorRunbookConfigSchema.parse(clusterYamlConfig).validator;

console.error(
  'Loaded validator runbook configuration',
  util.inspect(validatorRunbookConfig, {
    depth: null,
    maxStringLength: null,
  })
);
