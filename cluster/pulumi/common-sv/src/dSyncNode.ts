// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DecentralizedSynchronizerMigrationConfig,
  SynchronizerMigrationSchema,
} from '@canton-network/splice-pulumi-common';
import { clusterYamlConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import { z } from 'zod';

import { StaticSvConfig } from './config';
import { withPhysicalSynchronizers } from './physicalSynchronizerConfig';
import { SyncNodeSchema } from './singleSvConfig';

export const dsyncConfigSchema = z.object({
  dsync: z
    .object({
      migration: SynchronizerMigrationSchema,
    })
    .optional(),
});
export type DsyncConfig = z.infer<typeof dsyncConfigSchema>;

export const dsyncConfig = dsyncConfigSchema.parse(clusterYamlConfig);

export const dSyncMigrationConfig = dsyncConfig.dsync
  ? new DecentralizedSynchronizerMigrationConfig(dsyncConfig.dsync.migration)
  : undefined;

const DSyncNodeSchema = SyncNodeSchema.extend({}).transform(config =>
  withPhysicalSynchronizers(config, dSyncMigrationConfig?.allMigrations ?? [])
);
const AllDSyncNodesConfigurationSchema = z.record(z.string(), DSyncNodeSchema);
const DSyncNodesConfigurationSchema = z.object({
  dsyncNodes: AllDSyncNodesConfigurationSchema.optional(),
});

type AllDSyncNodesConfig = z.infer<typeof AllDSyncNodesConfigurationSchema>;
export type SingleDSyncNodeConfiguration = z.infer<typeof DSyncNodeSchema>;

export const clusterDSyncNodesConfiguration: AllDSyncNodesConfig =
  DSyncNodesConfigurationSchema.parse(clusterYamlConfig)?.dsyncNodes || {};

export const configForDSyncNode = (dSyncNodeName: string): SingleDSyncNodeConfiguration => {
  return clusterDSyncNodesConfiguration[dSyncNodeName];
};

export const staticSvConfigFromDSyncNodeConfig = (nodeName: string): StaticSvConfig => {
  return {
    auth0ValidatorAppName: '',
    auth0SvAppName: '',
    cometBft: {
      validator: {
        keyAddress: '',
      },
      nodeIndex: 0,
      id: '',
    },
    nodeName: nodeName,
    ingressName: nodeName,
    onboardingName: nodeName,
  };
};
