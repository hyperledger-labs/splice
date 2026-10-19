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
import { mergeConfigFragments } from '@canton-network/splice-pulumi-common/src/config/configLoader';
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
const PhysicalSynchronizerConfigOverridesSchema = z
  .object({
    mediator: SvMediatorConfigSchema.partial().optional(),
    sequencer: SvSequencerConfigSchema.partial().optional(),
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

type SyncNodeConfigWithOverrides = {
  physicalSynchronizerOverrides: z.infer<typeof PhysicalSynchronizersConfigOverridesSchema>;
  sequencer: SvSequencerConfig;
  mediator: SvMediatorConfig;
};

// Replaces the top-level sequencer and mediator configs with a per-migration `physicalSynchronizers`
// map, in which each migration's config is the top-level config merged with its overrides.
// Implicit config merging is confusing but for now I don't really see a better way of introducing these overrides.
// Perhaps a proper revisit of the synchronizerMigration config structure will lead to a better solution here.
export function withPhysicalSynchronizers<C extends SyncNodeConfigWithOverrides>(
  { physicalSynchronizerOverrides, sequencer, mediator, ...rest }: C,
  migrations: { id: number }[]
): Omit<C, keyof SyncNodeConfigWithOverrides> & {
  physicalSynchronizers: Record<
    number,
    { mediator: SvMediatorConfig; sequencer: SvSequencerConfig }
  >;
} {
  const physicalSynchronizers = migrations.map(migration => [
    migration.id,
    {
      mediator: mergeConfigFragments(
        mediator,
        physicalSynchronizerOverrides[migration.id]?.mediator || {}
      ) as SvMediatorConfig,
      sequencer: mergeConfigFragments(
        sequencer,
        physicalSynchronizerOverrides[migration.id]?.sequencer || {}
      ) as SvSequencerConfig,
    },
  ]);
  return {
    ...rest,
    physicalSynchronizers: _.fromPairs(physicalSynchronizers),
  };
}
