import * as pulumi from '@pulumi/pulumi';
import _ from 'lodash';
import {
  Auth0Client,
  BackupConfig,
  BackupLocation,
  BootstrappingDumpConfig,
  CnInput,
  ExpectedValidatorOnboarding,
  SvIdKey,
  ValidatorTopupConfig,
  svKeyFromSecret,
  DecentralizedSynchronizerMigrationConfig,
  ApprovedSvIdentity,
  daSupportApprovedIdentities,
  config,
  approvedSvIdentities,
} from 'splice-pulumi-common';
import { StaticCometBftConfigWithNodeName, svConfigs } from 'splice-pulumi-common-sv';
import { SequencerPruningConfig, StaticSvConfig } from 'splice-pulumi-common-sv/src/config';

import { InstalledSv, SvOnboarding, installSvNode } from './sv';

interface DsoArgs {
  dsoSize: number;

  auth0Client: Auth0Client;
  approvedSvIdentities: ApprovedSvIdentity[];
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[]; // Only used by the sv1
  isDevNet: boolean;
  periodicBackupConfig?: BackupConfig;
  identitiesBackupLocation: BackupLocation;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  splitPostgresInstances: boolean;
  sequencerPruningConfig: SequencerPruningConfig;
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
    extraApprovedSvIdentities: ApprovedSvIdentity[],
    expectedValidatorOnboardings: ExpectedValidatorOnboarding[],
    isFirstSv = false
  ) {
    const defaultApprovedSvIdentities = approvedSvIdentities();

    const identities = _.uniqBy(
      [
        ...defaultApprovedSvIdentities,
        ...extraApprovedSvIdentities,
        ...this.args.approvedSvIdentities,
      ],
      'name'
    );

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
        approvedSvIdentities: identities,
        expectedValidatorOnboardings,
        isDevNet: this.args.isDevNet,
        periodicBackupConfig: this.args.periodicBackupConfig,
        identitiesBackupLocation: this.args.identitiesBackupLocation,
        bootstrappingDumpConfig: this.args.bootstrappingDumpConfig,
        topupConfig: this.args.topupConfig,
        splitPostgresInstances: this.args.splitPostgresInstances,
        sequencerPruningConfig: this.args.sequencerPruningConfig,
        disableOnboardingParticipantPromotionDelay:
          this.args.disableOnboardingParticipantPromotionDelay,
        onboardingPollingInterval: this.args.onboardingPollingInterval,
        sweep: svConf.sweep,
      },
      this.args.decentralizedSynchronizerUpgradeConfig
    );
  }

  private async installDso() {
    const [sv1Conf, ...restSvConfs] = svConfigs.slice(0, this.args.dsoSize);

    const keys = restSvConfs.reduce<Record<string, pulumi.Output<SvIdKey>>>((acc, conf) => {
      return {
        ...acc,
        [conf.onboardingName]: svKeyFromSecret(conf.nodeName.replace('-', '')),
      };
    }, {});

    const additionalSvIdentities: ApprovedSvIdentity[] = Object.entries(keys)
      .map<ApprovedSvIdentity>(([onboardingName, keys]) => ({
        name: onboardingName,
        publicKey: keys.publicKey,
        rewardWeightBps: 10000, // if already defined in approved-sv-id-values-$CLUSTER.yaml, this will be ignored.
      }))
      .concat(daSupportApprovedIdentities);

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

    const sv1SvRewardWeightBps = 140_000;

    const runningMigration = this.args.decentralizedSynchronizerUpgradeConfig.isRunningMigration();
    const sv1 = await this.installSvNode(
      sv1Conf,
      runningMigration
        ? { type: 'domain-migration' }
        : {
            type: 'found-dso',
            sv1SvRewardWeightBps,
            roundZeroDuration: config.optionalEnv('ROUND_ZERO_DURATION'),
          },
      {
        sv1: sv1CometBftConf,
        peers: peerCometBftConfs,
      },
      additionalSvIdentities,
      this.args.expectedValidatorOnboardings,
      true
    );

    const restSvs = await Promise.all(
      restSvConfs.map(conf => {
        const onboarding: SvOnboarding = runningMigration
          ? { type: 'domain-migration' }
          : this.joinViaSv1(sv1.svApp, keys[conf.onboardingName]);
        const cometBft = {
          sv1: sv1CometBftConf,
          peers: peerCometBftConfs.filter(c => c.id !== conf.cometBft.id), // remove self from peer list
        };

        return this.installSvNode(conf, onboarding, cometBft, additionalSvIdentities, [], false);
      })
    );

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
