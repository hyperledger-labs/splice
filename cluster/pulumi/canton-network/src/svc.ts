import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  BackupLocation,
  BootstrappingDumpConfig,
  CnInput,
  ExpectedValidatorOnboarding,
  REPO_ROOT,
  SvIdKey,
  ValidatorTopupConfig,
  loadYamlFromFile,
  svKeyFromSecret,
  GlobalDomainMigrationConfig,
} from 'cn-pulumi-common';
import _ from 'lodash';

import {
  ApprovedSvIdentity,
  InstalledSv,
  SequencerPruningConfig,
  SvOnboarding,
  installSvNode,
} from './sv';
import svconfs, { StaticCometBftConfigWithNodeName, StaticSvConfig } from './svconfs';

interface SvcArgs {
  svcSize: number;

  auth0Client: Auth0Client;
  approvedSvIdentities: ApprovedSvIdentity[];
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[]; // Only used by the founder
  isDevNet: boolean;
  periodicBackupConfig?: BackupConfig;
  identitiesBackupLocation: BackupLocation;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  splitPostgresInstances: boolean;
  sequencerPruningConfig: SequencerPruningConfig;
  globalDomainUpgradeConfig: GlobalDomainMigrationConfig;
  onboardingPollingInterval?: string;
  disableOnboardingParticipantPromotionDelay: boolean;
}

export class Svc extends pulumi.ComponentResource {
  args: SvcArgs;
  founder: Promise<InstalledSv>;
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
      founder: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    },
    extraApprovedSvIdentities: ApprovedSvIdentity[],
    expectedValidatorOnboardings: ExpectedValidatorOnboarding[],
    isFounder = false,
    cometBftSyncSource?: k8s.helm.v3.Release
  ) {
    const defaultApprovedSvIdentities = (
      this.args.isDevNet
        ? loadYamlFromFile(
            `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/approved-sv-id-values-dev.yaml`
          )
        : loadYamlFromFile(
            `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/approved-sv-id-values-test.yaml`
          )
    ).approvedSvIdentities;

    const approvedSvIdentities = _.uniqBy(
      [
        ...defaultApprovedSvIdentities,
        ...extraApprovedSvIdentities,
        ...this.args.approvedSvIdentities,
      ],
      'name'
    );

    return installSvNode(
      {
        isFounder,
        nodeName: svConf.nodeName,
        onboardingName: svConf.onboardingName,
        nodeConfigs,
        cometBft: svConf.cometBft,
        validatorWalletUser: svConf.validatorWalletUser,
        auth0ValidatorAppName: svConf.auth0ValidatorAppName,
        onboarding,
        auth0Client: this.args.auth0Client,
        approvedSvIdentities,
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
      },
      this.args.globalDomainUpgradeConfig,
      cometBftSyncSource
    );
  }

  private async installSvc() {
    const [founderConf, ...restSvConfs] = svconfs.slice(0, this.args.svcSize);

    const keys = restSvConfs.reduce<Record<string, pulumi.Output<SvIdKey>>>((acc, conf) => {
      return {
        ...acc,
        [conf.onboardingName]: svKeyFromSecret(conf.nodeName.replace('-', '')),
      };
    }, {});

    const additionalSvIdentities: ApprovedSvIdentity[] = Object.entries(keys).map(
      ([onboardingName, keys]) => ({
        name: onboardingName,
        publicKey: keys.publicKey,
        rewardWeightBps: 10000, // if already defined in approved-sv-id-values-$CLUSTER.yaml, this will be ignored.
      })
    );

    const founderCometBftConf = { ...founderConf.cometBft, nodeName: founderConf.nodeName };
    const peerCometBftConfs = restSvConfs.map(conf => ({
      ...conf.cometBft,
      nodeName: conf.nodeName,
    }));

    const foundingSvRewardWeightBps = 140_000;
    const initialTickDurationS = 600;
    // chosen for $1/360 days at above tick duration
    const initialHoldingFee =
      Math.round((100000_00000 * 1.0) / 360.0 / ((24.0 * 60.0 * 60.0) / initialTickDurationS)) /
      100000_00000;

    const runningMigration = this.args.globalDomainUpgradeConfig.isRunningMigration();
    const founder = await this.installSvNode(
      founderConf,
      runningMigration
        ? { type: 'domain-migration' }
        : {
            type: 'found-collective',
            foundingSvRewardWeightBps,
            roundZeroDuration: process.env.ROUND_ZERO_DURATION,
            initialTickDurationS,
            initialHoldingFee,
          },
      {
        founder: founderCometBftConf,
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
          : this.joinViaSv1(founder.svApp, keys[conf.onboardingName]);
        const cometBft = {
          founder: founderCometBftConf,
          peers: peerCometBftConfs.filter(c => c.id !== conf.cometBft.id), // remove self from peer list
        };

        return this.installSvNode(
          conf,
          onboarding,
          cometBft,
          additionalSvIdentities,
          [],
          false,
          founder.svApp
        );
      })
    );

    return { founder, allSvs: [founder, ...restSvs] };
  }

  constructor(name: string, args: SvcArgs, opts?: pulumi.ComponentResourceOptions) {
    super('canton:network:svc', name, args, opts);
    this.args = args;

    const svc = this.installSvc();

    // eslint-disable-next-line promise/prefer-await-to-then
    this.founder = svc.then(r => r.founder);
    // eslint-disable-next-line promise/prefer-await-to-then
    this.allSvs = svc.then(r => r.allSvs);

    this.registerOutputs({});
  }
}
