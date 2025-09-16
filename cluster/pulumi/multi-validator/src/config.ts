// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

import { LogLevelSchema } from '../../common';
import { K8sResourceSchema } from '../../common/src/config/configSchema';

export const EnvironmentVariableSchema = z.object({
  name: z.string(),
  value: z.string(),
});

export type EnvironmentVariable = z.infer<typeof EnvironmentVariableSchema>;

export const MultiValidatorConfigSchema = z.object({
  multiValidator: z
    .object({
      postgresPvcSize: z.string().optional(),
      requiresOnboardingSecret: z.boolean().default(false),
      extraValidatorEnvVars: z.array(EnvironmentVariableSchema).default([]),
      extraParticipantEnvVars: z.array(EnvironmentVariableSchema).default([]),
      logLevel: LogLevelSchema.optional().default('INFO'),
      resources: z
        .object({
          postgres: K8sResourceSchema,
          validator: K8sResourceSchema,
          participant: K8sResourceSchema,
        })
        .optional(),
    })
    .optional(),
});

export type Config = z.infer<typeof MultiValidatorConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
export const multiValidatorConfig =
  MultiValidatorConfigSchema.parse(clusterYamlConfig).multiValidator;
