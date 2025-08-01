// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  ApprovedSvIdentity,
  Auth0Client,
  BackupConfig,
  BackupLocation,
  BootstrappingDumpConfig,
  CnInput,
  ExpectedValidatorOnboarding,
  SvIdKey,
  SvCometBftGovernanceKey,
  ValidatorTopupConfig,
} from 'splice-pulumi-common';
import { SweepConfig } from 'splice-pulumi-common-validator';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

import { SingleSvConfiguration } from './singleSvConfig';
import {
  StaticCometBftConfig,
  StaticCometBftConfigWithNodeName,
} from './synchronizer/cometbftConfig';

export type SvOnboarding =
  | { type: 'domain-migration' }
  | {
      type: 'found-dso';
      sv1SvRewardWeightBps: number;
      roundZeroDuration?: string;
      initialRound?: string;
    }
  | {
      type: 'join-with-key';
      keys: CnInput<SvIdKey>;
      sponsorRelease: pulumi.Resource;
      sponsorApiUrl: string;
    };

export interface ScanBigQueryConfig {
  dataset: string;
  prefix: string;
}

export interface StaticSvConfig {
  nodeName: string;
  ingressName: string;
  onboardingName: string;
  validatorWalletUser?: string;
  auth0ValidatorAppName: string;
  auth0SvAppName: string;
  cometBft: StaticCometBftConfig;
  onboardingPollingInterval?: string;
  sweep?: SweepConfig;
  scanBigQuery?: ScanBigQueryConfig;
}

export type SequencerPruningConfig = {
  enabled: boolean;
  pruningInterval?: string;
  retentionPeriod?: string;
};

export interface SvConfig extends StaticSvConfig, SingleSvConfiguration {
  isFirstSv: boolean;
  auth0Client: Auth0Client;
  nodeConfigs: {
    sv1: StaticCometBftConfigWithNodeName;
    peers: StaticCometBftConfigWithNodeName[];
  };
  onboarding: SvOnboarding;
  approvedSvIdentities: ApprovedSvIdentity[];
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[];
  isDevNet: boolean;
  periodicBackupConfig?: BackupConfig;
  identitiesBackupLocation: BackupLocation;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  sequencerPruningConfig: SequencerPruningConfig;
  splitPostgresInstances: boolean;
  disableOnboardingParticipantPromotionDelay: boolean;
  onboardingPollingInterval?: string;
  cometBftGovernanceKey?: CnInput<SvCometBftGovernanceKey>;
  initialRound?: string;
}

export const SvConfigSchema = z.object({
  sv: z
    .object({
      cometbft: z
        .object({
          volumeSize: z.string().optional(),
        })
        .optional(),
      scan: z
        .object({
          enableImportUpdatesBackfill: z.boolean().optional(),
          rateLimit: z
            .object({
              acs: z
                .object({
                  limit: z.number(),
                })
                .optional(),
            })
            .optional(),
        })
        .optional(),
      synchronizer: z
        .object({
          skipInitialization: z.boolean().default(false),
          // This can be used on clusters like CILR where we usually would expect to skip initialization but the sv runbook gets reset periodically.
          forceSvRunbookInitialization: z.boolean().default(false),
          topologyChangeDelay: z.string().optional(),
        })
        .optional(),
    })
    .optional(),
  initialRound: z.number().optional(),
});

export type Config = z.infer<typeof SvConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
export const svsConfig = SvConfigSchema.parse(clusterYamlConfig).sv;

// eslint-disable-next-line
// @ts-ignore
export const initialRound = SvConfigSchema.parse(clusterYamlConfig).initialRound;

export const updateHistoryBackfillingValues = svsConfig?.scan?.enableImportUpdatesBackfill
  ? {
      updateHistoryBackfilling: {
        enabled: true,
        importUpdatesEnabled: true,
        batchSize: 100,
      },
    }
  : undefined;
