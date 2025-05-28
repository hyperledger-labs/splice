// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { KmsConfigSchema } from 'splice-pulumi-common';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const Validator1ConfigSchema = z.object({
  validator1: z
    .object({
      kms: KmsConfigSchema.optional(),
      participantPruningSchedule: z
        .object({
          cron: z.string(),
          maxDuration: z.string(),
          retention: z.string(),
        })
        .optional(),
      deduplicationDuration: z.string().optional(),
    })
    .optional(),
});

export type Config = z.infer<typeof Validator1ConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
export const validator1Config = Validator1ConfigSchema.parse(clusterYamlConfig).validator1;
