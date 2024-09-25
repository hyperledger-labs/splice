import { SvOnboarding } from 'canton-network-pulumi-deployment/src/sv';
import { SweepConfig } from 'canton-network-pulumi-deployment/src/validator';
import {
  ApprovedSvIdentity,
  Auth0Client,
  BackupConfig,
  BackupLocation,
  BootstrappingDumpConfig,
  ExpectedValidatorOnboarding,
  ValidatorTopupConfig,
} from 'splice-pulumi-common';

import {
  StaticCometBftConfig,
  StaticCometBftConfigWithNodeName,
} from './synchronizer/cometbftConfig';

export interface StaticSvConfig {
  nodeName: string;
  ingressName: string;
  onboardingName: string;
  validatorWalletUser: string;
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
}
