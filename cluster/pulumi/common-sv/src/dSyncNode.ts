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

const DSyncSchema = z
  .object({
    migration: SynchronizerMigrationSchema,
    nodes: z.record(z.string(), SyncNodeSchema).default({}),
  })
  .transform(dsync => {
    const migrationConfig = new DecentralizedSynchronizerMigrationConfig(dsync.migration);
    return {
      migrationConfig,
      nodes: Object.fromEntries(
        Object.entries(dsync.nodes).map(([nodeName, nodeConfig]) => [
          nodeName,
          withPhysicalSynchronizers(nodeConfig, migrationConfig.allMigrations),
        ])
      ),
    };
  });
const DSyncsConfigurationSchema = z.object({
  dsyncs: z.record(z.string(), DSyncSchema).optional(),
});

export type DSyncConfiguration = z.infer<typeof DSyncSchema>;
export type SingleDSyncNodeConfiguration = DSyncConfiguration['nodes'][string];

export const clusterDSyncsConfiguration: Record<string, DSyncConfiguration> = Object.fromEntries(
  Object.entries(DSyncsConfigurationSchema.parse(clusterYamlConfig).dsyncs || {}).map(
    ([dsyncName, dsync]) => [
      dsyncName,
      {
        ...dsync,
        // The full node name is `${dsyncName}-${nodeName}`, where nodeName is the key under `nodes` in the yaml.
        nodes: Object.fromEntries(
          Object.entries(dsync.nodes).map(([nodeName, nodeConfig]) => [
            `${dsyncName}-${nodeName}`,
            nodeConfig,
          ])
        ),
      },
    ]
  )
);

// Full node names (`${dsyncName}-${nodeName}`) are used for namespaces and stack names, so they must be unique across all dsyncs.
const dsyncNameByNodeName = new Map<string, string>();
for (const [dsyncName, dsync] of Object.entries(clusterDSyncsConfiguration)) {
  for (const nodeName of Object.keys(dsync.nodes)) {
    const existing = dsyncNameByNodeName.get(nodeName);
    if (existing !== undefined) {
      throw new Error(
        `dsync node name ${nodeName} is used in both dsync ${existing} and dsync ${dsyncName}`
      );
    }
    dsyncNameByNodeName.set(nodeName, dsyncName);
  }
}

const dsyncForNode = (dSyncNodeName: string): DSyncConfiguration => {
  const dsyncName = dsyncNameByNodeName.get(dSyncNodeName);
  if (dsyncName === undefined) {
    throw new Error(
      `No dsync found containing node ${dSyncNodeName}, make sure SPLICE_DSYNC_NODE is of the form <dsyncName>-<nodeName> from the config`
    );
  }
  return clusterDSyncsConfiguration[dsyncName];
};

export const allDSyncNodeNames = (): string[] => {
  return Array.from(dsyncNameByNodeName.keys());
};

export const dSyncNodeNamesInSameDSync = (dSyncNodeName: string): string[] => {
  return Object.keys(dsyncForNode(dSyncNodeName).nodes);
};

export const configForDSyncNode = (dSyncNodeName: string): SingleDSyncNodeConfiguration => {
  return dsyncForNode(dSyncNodeName).nodes[dSyncNodeName];
};

export const dSyncMigrationConfigForNode = (
  dSyncNodeName: string
): DecentralizedSynchronizerMigrationConfig => {
  return dsyncForNode(dSyncNodeName).migrationConfig;
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
