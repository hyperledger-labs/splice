import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  CnInput,
  ExpectedValidatorOnboarding,
  REPO_ROOT,
  SvIdKey,
  ValidatorTopupConfig,
  loadYamlFromFile,
  svKeyFromSecret,
} from 'cn-pulumi-common';
import _ from 'lodash';

import { GlobalDomainUpgradeConfig } from './globalDomainNode';
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
  backupConfig?: BackupConfig;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  splitPostgresInstances: boolean;
  sequencerPruningConfig: SequencerPruningConfig;
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig;
}
export class Svc extends pulumi.ComponentResource {
  args: SvcArgs;
  founder: Promise<InstalledSv>;
  allSvs: Promise<InstalledSv[]>;

  private joinViaSv1(sv1: pulumi.Resource, keys: CnInput<SvIdKey>): SvOnboarding {
    return {
      type: 'join-with-key',
      sponsorApiUrl: `http://sv-app-${this.args.globalDomainUpgradeConfig.activeGlobalDomainId}.sv-1:5014`,
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
        backupConfig: this.args.backupConfig,
        bootstrappingDumpConfig: this.args.bootstrappingDumpConfig,
        topupConfig: this.args.topupConfig,
        splitPostgresInstances: this.args.splitPostgresInstances,
        sequencerPruningConfig: this.args.sequencerPruningConfig,
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

    const additionalSvIdentities = Object.entries(keys).map(([onboardingName, keys]) => ({
      name: onboardingName,
      publicKey: keys.publicKey,
    }));

    const founderCometBftConf = { ...founderConf.cometBft, nodeName: founderConf.nodeName };
    const peerCometBftConfs = restSvConfs.map(conf => ({
      ...conf.cometBft,
      nodeName: conf.nodeName,
    }));

    const founder = await this.installSvNode(
      founderConf,
      { type: 'found-collective' },
      {
        founder: founderCometBftConf,
        peers: peerCometBftConfs,
      },
      additionalSvIdentities,
      this.args.expectedValidatorOnboardings
    );

    const restSvs = await Promise.all(
      restSvConfs.map(conf => {
        const onboarding = this.joinViaSv1(founder.svApp, keys[conf.onboardingName]);
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
