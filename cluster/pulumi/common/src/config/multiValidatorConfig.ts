// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import { z } from 'zod';

import { config, EnvVarConfigSchema, LogLevelSchema, SplicePostgresSchema } from '.';
import { K8sResourceSchema } from './configSchema';

export const MultiValidatorConfigSchema = z.object({
  multiValidator: z
    .object({
      instanceCount: z.number().default(0),
      postgresPvcSize: z.string().optional(),
      // Multi-validator needs to be migrated
      postgres: SplicePostgresSchema.default({
        deployment: 'legacy-helm-chart',
      }),
      requiresOnboardingSecret: z.boolean().default(false),
      extraValidatorEnvVars: z.array(EnvVarConfigSchema).default([]),
      extraParticipantEnvVars: z.array(EnvVarConfigSchema).default([]),
      logLevel: LogLevelSchema.optional().default('INFO'),
      apiRequestLogLevel: LogLevelSchema.optional().default('DEBUG'),
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

export type MultiValidatorConfig = NonNullable<
  z.infer<typeof MultiValidatorConfigSchema>['multiValidator']
>;

// eslint-disable-next-line
// @ts-ignore
export const multiValidatorConfig: MultiValidatorConfig | undefined =
  MultiValidatorConfigSchema.parse(clusterYamlConfig).multiValidator;

export const numNodesPerInstance = 10;
