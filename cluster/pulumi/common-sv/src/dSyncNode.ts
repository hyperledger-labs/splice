// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { clusterYamlConfig } from "@canton-network/splice-pulumi-common/src/config/config";
import { SyncNodeSchema } from "./singleSvConfig";
import { z } from "zod";
import { StaticSvConfig } from "./config";
import { DecentralizedSynchronizerMigrationConfig, SynchronizerMigrationSchema } from "@canton-network/splice-pulumi-common";
import { mergeConfigFragments } from "@canton-network/splice-pulumi-common/src/config/configLoader";
import { SvMediatorConfig, SvSequencerConfig } from "./physicalSynchronizerConfig";
import * as _ from 'lodash';

export const dsyncConfigSchema = z.object({
  dsync: z.object({
    migration: SynchronizerMigrationSchema,
  }).optional(),
});
export type DsyncConfig = z.infer<typeof dsyncConfigSchema>;

export const dsyncConfig = dsyncConfigSchema.parse(clusterYamlConfig);

export const dSyncMigrationConfig = dsyncConfig.dsync ? new DecentralizedSynchronizerMigrationConfig(dsyncConfig.dsync.migration) : undefined;

const DSyncNodeSchema = SyncNodeSchema.extend({}).transform(({ physicalSynchronizerOverrides, sequencer, mediator, ...svConfig }) => {
  // Implicit config merging is confusing but for now I don't really see a better way of introducting these overrides.
  // Perhaps a proper revisit of the synchronizerMigration config structure will lead to a better solution here.
  const physicalSynchronizers = dSyncMigrationConfig?.allMigrations.map(
    migration => [
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
    ]
  );
  return {
    ...svConfig,
    physicalSynchronizers: _.fromPairs(physicalSynchronizers || []),
  };
});
;
const AllDSyncNodesConfigurationSchema = z.record(z.string(), DSyncNodeSchema);
const DSyncNodesConfigurationSchema = z.object({
  dsyncNodes: AllDSyncNodesConfigurationSchema.optional(),
});

type AllDSyncNodesConfig = z.infer<typeof AllDSyncNodesConfigurationSchema>;
export type SingleDSyncNodeConfiguration = z.infer<typeof DSyncNodeSchema>;

export const clusterDSyncNodesConfiguration: AllDSyncNodesConfig = DSyncNodesConfigurationSchema.parse(clusterYamlConfig)?.dsyncNodes || {};

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
  }
};

