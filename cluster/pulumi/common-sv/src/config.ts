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
    }
  | {
      type: 'join-with-key';
      keys: CnInput<SvIdKey>;
      sponsorRelease: pulumi.Resource;
      sponsorApiUrl: string;
    };

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
}

export type SequencerPruningConfig = {
  enabled: boolean;
  pruningInterval?: string;
  retentionPeriod?: string;
};

export interface SvConfig extends StaticSvConfig {
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
}

export const SvConfigSchema = z.object({
  sv: z
    .object({
      cometbft: z
        .object({
          volumeSize: z.string().optional(),
        })
        .optional(),
    })
    .optional(),
});

export type Config = z.infer<typeof SvConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
export const svConfig = SvConfigSchema.parse(clusterYamlConfig).sv;
