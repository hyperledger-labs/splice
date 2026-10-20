// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BucketConfig,
  BucketLocation,
  BootstrappingDumpConfig,
  CnInput,
  ExpectedValidatorOnboarding,
  SvCometBftGovernanceKey,
  SvIdKey,
  ValidatorTopupConfig,
  RateLimitSchema,
  CnChartVersion,
} from '@canton-network/splice-pulumi-common';
import { SweepConfig } from '@canton-network/splice-pulumi-common-validator';
import { clusterYamlConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import { z } from 'zod';

import { BulkStorageBuckets } from './bulkStorage';
import { SingleSvConfiguration } from './singleSvConfig';

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
      sponsorRelease?: pulumi.Resource;
      sponsorApiUrl: string;
      sponsorScanUrl: string;
    };

export interface StaticSvConfigBasic {
  nodeName: string;
  ingressName: string;
  onboardingName: string;
  sweep?: SweepConfig;
}

export interface StaticSvConfig extends StaticSvConfigBasic {
  validatorWalletUser?: string;
  auth0ValidatorAppName: string;
  auth0SvAppName: string;
  onboardingPollingInterval?: string;
  svIdKeySecretName?: string;
  cometBftGovernanceKeySecretName?: string;
}

export interface SvConfig extends StaticSvConfig, SingleSvConfiguration {
  isFirstSv: boolean;
  auth0Client: Auth0Client;
  onboarding: SvOnboarding;
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[];
  isDevNet: boolean;
  periodicBackupConfig?: BucketConfig;
  identitiesBackupLocation: BucketLocation;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  splitPostgresInstances: boolean;
  disableOnboardingParticipantPromotionDelay: boolean;
  onboardingPollingInterval?: string;
  cometBftGovernanceKey?: CnInput<SvCometBftGovernanceKey>;
  initialRound?: string;
  periodicTopologySnapshotConfig?: CnInput<BucketConfig>;
  version: CnChartVersion;
  bulkStorageBuckets?: BulkStorageBuckets;
}

export const TopologySnapshotSchema = z.object({
  backupInterval: z.string(),
  bucketName: z.string(),
  bucketSaKeySecret: z.string(),
  bucketSaIamAccount: z.string(),
  projectId: z.string(),
});

export const SvConfigSchema = z.object({
  sv: z
    .object({
      scan: z
        .object({
          rateLimit: z
            .object({
              acs: z
                .object({
                  limit: z.number(),
                })
                .optional(),
            })
            .optional(),
          externalRateLimits: RateLimitSchema,
        })
        .optional(),
      synchronizer: z
        .object({
          skipInitialization: z.boolean().default(false),
          // This can be used on clusters like CILR where we usually would expect to skip initialization but the sv runbook gets reset periodically.
          forceSvRunbookInitialization: z.boolean().default(false),
          sequencer: z
            .object({
              externalRateLimits: RateLimitSchema,
            })
            .optional(),
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
