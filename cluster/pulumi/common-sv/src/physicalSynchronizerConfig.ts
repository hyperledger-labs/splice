// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as _ from 'lodash';
import {
  CloudSqlConfigSchema,
  DecentralizedSynchronizerUpgradeConfig,
  EnvVarConfigSchema,
  K8sResourceSchema,
  spliceConfig,
  SplicePostgresSchema,
} from '@canton-network/splice-pulumi-common';
import { z } from 'zod';

export const CloudSqlWithOverrideConfigSchema = CloudSqlConfigSchema.partial()
  .default(spliceConfig.pulumiProjectConfig.cloudSql)
  .transform(sqlConfig => _.merge({}, spliceConfig.pulumiProjectConfig.cloudSql, sqlConfig));
export const SvMediatorConfigSchema = z
  .object({
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
    cloudSql: CloudSqlWithOverrideConfigSchema,
    // Mediator is either deployed on cloudSQL or is reset frequently, so we can skip migration
    splicePostgres: SplicePostgresSchema.default({
      postgresImage: 'postgres:18-trixie',
      deployment: 'docker-image',
    }),
    resources: K8sResourceSchema,
  })
  .strict();
export type SvMediatorConfig = z.infer<typeof SvMediatorConfigSchema>;
export const SvSequencerConfigSchema = z
  .object({
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
    cloudSql: CloudSqlWithOverrideConfigSchema,
    // Sequencer is either deployed on cloudSQL or is reset frequently, so we can skip migration
    splicePostgres: SplicePostgresSchema.default({
      postgresImage: 'postgres:18-trixie',
      deployment: 'docker-image',
    }),
    resources: K8sResourceSchema,
    enableAntiAffinity: z.boolean().default(true),
  })
  .strict();
export type SvSequencerConfig = z.infer<typeof SvSequencerConfigSchema>;
const CloudSqlOverrideSchema = z
  .object(
    _.mapValues(CloudSqlConfigSchema.shape, field =>
      field instanceof z.ZodDefault ? field.unwrap() : field
    )
  )
  .partial();
export const SvCantonBftConfigSchema = z
  .object({
    cloudSql: CloudSqlOverrideSchema,
    splicePostgres: SplicePostgresSchema,
  })
  .partial()
  .strict();
export type SvCantonBftConfig = Pick<SvSequencerConfig, 'cloudSql' | 'splicePostgres'>;
const PhysicalSynchronizerConfigOverridesSchema = z
  .object({
    mediator: SvMediatorConfigSchema.partial().optional(),
    sequencer: SvSequencerConfigSchema.partial().optional(),
    cantonBft: SvCantonBftConfigSchema.optional(),
  })
  .strict();
export const PhysicalSynchronizersConfigOverridesSchema = z
  .record(z.number(), PhysicalSynchronizerConfigOverridesSchema)
  .refine(
    physicalSynchronizers =>
      Object.keys(physicalSynchronizers).every(psid =>
        DecentralizedSynchronizerUpgradeConfig.allMigrations.some(
          migration => migration.id === Number(psid)
        )
      ),
    { error: 'physical synchronizer configuration must only include running synchronizers' }
  );
