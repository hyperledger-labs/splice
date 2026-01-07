// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  BackupLocation,
  BootstrappingDumpConfig,
  CnInput,
  config,
  DecentralizedSynchronizerMigrationConfig,
  ExpectedValidatorOnboarding,
  SvCometBftGovernanceKey,
  svCometBftGovernanceKeyFromSecret,
  SvIdKey,
  svKeyFromSecret,
  ValidatorTopupConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  approvedSvIdentities,
  configForSv,
  coreSvsToDeploy,
  initialRound,
  StaticCometBftConfigWithNodeName,
  StaticSvConfig,
  SvOnboarding,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';

import { InstalledSv, installSvNode } from './sv';

interface DsoArgs {
  auth0Client: Auth0Client;
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[]; // Only used by the sv1
  isDevNet: boolean;
  periodicBackupConfig?: BackupConfig;
  identitiesBackupLocation: BackupLocation;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  splitPostgresInstances: boolean;
  decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig;
  onboardingPollingInterval?: string;
  disableOnboardingParticipantPromotionDelay: boolean;
}

export class Dso extends pulumi.ComponentResource {
  args: DsoArgs;
  sv1: Promise<InstalledSv>;
  allSvs: Promise<InstalledSv[]>;

  private joinViaSv1(sv1: pulumi.Resource, keys: CnInput<SvIdKey>): SvOnboarding {
    return {
      type: 'join-with-key',
      sponsorApiUrl: `http://sv-app.sv-1:5014`,
      sponsorRelease: sv1,
      keys,
    };
  }

  private async installSvNode(
    svConf: StaticSvConfig,
    onboarding: SvOnboarding,
    nodeConfigs: {
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    },
    expectedValidatorOnboardings: ExpectedValidatorOnboarding[],
    isFirstSv = false,
    cometBftGovernanceKey: CnInput<SvCometBftGovernanceKey> | undefined = undefined,
    extraDependsOn: CnInput<pulumi.Resource>[] = []
  ) {
    return installSvNode(
      {
        isFirstSv,
        nodeName: svConf.nodeName,
        ingressName: svConf.ingressName,
        onboardingName: svConf.onboardingName,
        nodeConfigs,
        cometBft: svConf.cometBft,
        validatorWalletUser: svConf.validatorWalletUser,
        auth0ValidatorAppName: svConf.auth0ValidatorAppName,
        auth0SvAppName: svConf.auth0SvAppName,
        onboarding,
        auth0Client: this.args.auth0Client,
        expectedValidatorOnboardings,
        isDevNet: this.args.isDevNet,
        periodicBackupConfig: this.args.periodicBackupConfig,
        identitiesBackupLocation: this.args.identitiesBackupLocation,
        bootstrappingDumpConfig: this.args.bootstrappingDumpConfig,
        topupConfig: this.args.topupConfig,
        splitPostgresInstances: this.args.splitPostgresInstances,
        scanBigQuery: svConf.scanBigQuery,
        disableOnboardingParticipantPromotionDelay:
          this.args.disableOnboardingParticipantPromotionDelay,
        onboardingPollingInterval: this.args.onboardingPollingInterval,
        sweep: svConf.sweep,
        cometBftGovernanceKey,
        initialRound: initialRound?.toString(),
        ...configForSv(svConf.nodeName),
      },
      this.args.decentralizedSynchronizerUpgradeConfig,
      extraDependsOn
    );
  }

  private async installDso() {
    const relevantSvConfs = coreSvsToDeploy;
    const [sv1Conf, ...restSvConfs] = relevantSvConfs;

    const svIdKeys = restSvConfs.reduce<Record<string, pulumi.Output<SvIdKey>>>((acc, conf) => {
      const secretName = conf.svIdKeySecretName ?? conf.nodeName.replaceAll('-', '') + '-id';
      return {
        ...acc,
        [conf.onboardingName]: svKeyFromSecret(secretName),
      };
    }, {});

    const cometBftGovernanceKeys = relevantSvConfs
      .filter(conf => configForSv(conf.nodeName)?.participant?.kms)
      .reduce<Record<string, pulumi.Output<SvCometBftGovernanceKey>>>((acc, conf) => {
        const secretName =
          conf.cometBftGovernanceKeySecretName ??
          conf.nodeName.replaceAll('-', '') + '-cometbft-governance-key';
        return {
          ...acc,
          [conf.onboardingName]: svCometBftGovernanceKeyFromSecret(secretName),
        };
      }, {});

    const sv1CometBftConf = {
      ...sv1Conf.cometBft,
      nodeName: sv1Conf.nodeName,
      ingressName: sv1Conf.ingressName,
    };
    const peerCometBftConfs = restSvConfs.map(conf => ({
      ...conf.cometBft,
      nodeName: conf.nodeName,
      ingressName: conf.ingressName,
    }));

    const sv1SvRewardWeightBps = (() => {
      const found = approvedSvIdentities().find(
        identity => identity.name == sv1Conf.onboardingName
      );
      return found ? found.rewardWeightBps : 10000;
    })();

    const runningMigration = this.args.decentralizedSynchronizerUpgradeConfig.isRunningMigration();
    const sv1 = await this.installSvNode(
      sv1Conf,
      runningMigration
        ? { type: 'domain-migration' }
        : {
            type: 'found-dso',
            sv1SvRewardWeightBps,
            roundZeroDuration: config.optionalEnv('ROUND_ZERO_DURATION'),
            initialRound: initialRound?.toString(),
          },
      {
        sv1: sv1CometBftConf,
        peers: peerCometBftConfs,
      },
      this.args.expectedValidatorOnboardings,
      true,
      cometBftGovernanceKeys[sv1Conf.onboardingName]
    );

    // TODO(#893): long-term CantonBFT deployments should be robust enough to onboard in parallel again?
    const incrementalOnboarding =
      this.args.decentralizedSynchronizerUpgradeConfig.active.sequencer.enableBftSequencer;

    // recursive install function to allow injecting dependencies on previous svs
    const installSvNodes = async (
      configs: StaticSvConfig[],
      previousSvs: InstalledSv[] = []
    ): Promise<InstalledSv[]> => {
      if (configs.length === 0) {
        return previousSvs;
      }
      const [conf, ...remainingConfigs] = configs;

      const onboarding: SvOnboarding = runningMigration
        ? { type: 'domain-migration' }
        : this.joinViaSv1(sv1.svApp, svIdKeys[conf.onboardingName]);
      const cometBft = {
        sv1: sv1CometBftConf,
        peers: peerCometBftConfs.filter(c => c.id !== conf.cometBft.id), // remove self from peer list
      };

      const newSv = await this.installSvNode(
        conf,
        onboarding,
        cometBft,
        [],
        false,
        cometBftGovernanceKeys[conf.onboardingName],
        incrementalOnboarding ? previousSvs.map(sv => sv.svApp) : []
      );
      return installSvNodes(remainingConfigs, [...previousSvs, newSv]);
    };
    const restSvs = await installSvNodes(restSvConfs);

    return { sv1, allSvs: [sv1, ...restSvs] };
  }

  constructor(name: string, args: DsoArgs, opts?: pulumi.ComponentResourceOptions) {
    super('canton:network:dso', name, args, opts);
    this.args = args;

    const dso = this.installDso();

    // eslint-disable-next-line promise/prefer-await-to-then
    this.sv1 = dso.then(r => r.sv1);
    // eslint-disable-next-line promise/prefer-await-to-then
    this.allSvs = dso.then(r => r.allSvs);

    this.registerOutputs({});
  }
}
