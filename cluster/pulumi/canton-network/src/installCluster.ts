import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  bootstrapDataBucketSpec,
  BootstrappingDumpConfig,
  CnInput,
  domainFeesConfig,
  envFlag,
  infraStack,
  InfrastructureOutputs,
  installCNHelmChartByNamespaceName,
  isDevNet,
  loadYamlFromFile,
  REPO_ROOT,
  sequencerPruningConfig,
  SvIdKey,
  svKeyFromSecret,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';

import { installDocs } from './docs';
import { configureForwardAll } from './gateway';
import { GlobalDomainUpgradeConfig } from './globalDomainNode';
import { installSplitwell } from './splitwell';
import { installSvNode, SvOnboarding } from './sv';
import { installValidator1 } from './validator1';

/// Toplevel Chart Installs

console.error(`Launching with isDevNet: ${isDevNet}`);

// This flag determines whether to add a approved SV entry of 'DA-Helm-Test-Node'
// An 'DA-Helm-Test-Node' entry is already added to `approved-sv-id-values-dev.yaml` so it is added by default for devnet deployment.
// This flag is only relevant to non-devnet deployment.
const approveSvRunbook = envFlag('APPROVE_SV_RUNBOOK');
if (approveSvRunbook) {
  console.error('Approving SV used in SV runbook');
}

const singleSv = envFlag('SINGLE_SV') || !isDevNet;
if (singleSv) {
  console.error('Launching with a single SV');
}

// This flag determines whether to split postgres instances per app, or have one per namespace.
// By default, we split instances on CloudSQL (where we expect longer-living environments, thus want to support backup&recovery),
// but not on k8s-deployed postgres (where we optimize for faster deployment).
// One can force splitting them by setting SPLIT_POSTGRES_INSTANCES to true.
const splitPostgresInstances = envFlag('SPLIT_POSTGRES_INSTANCES') || envFlag('ENABLE_CLOUD_SQL');

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

type ApprovedSvIdentity = {
  name: string;
  publicKey: string;
};

const bootstrappingConfig: BootstrapCliConfig = process.env.BOOTSTRAPPING_CONFIG
  ? JSON.parse(process.env.BOOTSTRAPPING_CONFIG)
  : undefined;

const globalDomainUpgradeConfig: GlobalDomainUpgradeConfig = GlobalDomainUpgradeConfig.fromEnv();

const sv2Key = svKeyFromSecret('sv2');
const sv3Key = svKeyFromSecret('sv3');
const sv4Key = svKeyFromSecret('sv4');

const svRunbookApprovedSvIdentities = [
  {
    name: 'DA-Helm-Test-Node',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==',
  },
];

const sv234NameSet = new Set<string>([
  'Canton-Foundation-2',
  'Canton-Foundation-3',
  'Canton-Foundation-4',
]);

const allApprovedSvIdentities = (
  isDevNet
    ? loadYamlFromFile(
        `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/approved-sv-id-values-dev.yaml`
      )
    : loadYamlFromFile(
        `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/approved-sv-id-values-test.yaml`
      )
).approvedSvIdentities.concat(approveSvRunbook ? svRunbookApprovedSvIdentities : []);

const approvedSvIdentities = singleSv
  ? allApprovedSvIdentities.filter((id: ApprovedSvIdentity) => !sv234NameSet.has(id.name))
  : allApprovedSvIdentities;

function joinViaSv1(sv1: pulumi.Resource, keys: CnInput<SvIdKey>): SvOnboarding {
  return {
    type: 'join-with-key',
    sponsorApiUrl: `http://sv-app-${globalDomainUpgradeConfig.activeGlobalDomainId}.sv-1:5014`,
    sponsorRelease: sv1,
    keys,
  };
}

const splitwellOnboarding = {
  name: 'splitwell',
  secret: 'splitwellsecret',
  expiresIn: '24h',
};

const validator1Onboarding = {
  name: 'validator1',
  secret: 'validator1secret',
  expiresIn: '24h',
};

const standaloneValidatorOnboarding = {
  name: 'validator',
  secret: 'validatorsecret',
  expiresIn: '24h',
};

let backupConfig: BackupConfig | undefined;
let bootstrappingDumpConfig: BootstrappingDumpConfig | undefined;

export async function installCluster(auth0Client: Auth0Client): Promise<void> {
  const bootstrapBucketSpec = await bootstrapDataBucketSpec('da-cn-devnet', 'da-cn-data-dumps');

  if (!isDevNet) {
    backupConfig = { backupInterval: '10m', bucket: bootstrapBucketSpec };
  }

  const topupConfig: ValidatorTopupConfig = {
    targetThroughput: domainFeesConfig.targetThroughput,
    minTopupInterval: domainFeesConfig.minTopupInterval,
  };

  if (bootstrappingConfig) {
    const end = new Date(Date.parse(bootstrappingConfig.date));
    // We search within an interval of 24 hours. Given that we usually backups every 10min, this gives us
    // more than enough of a threshold to make sure each node has one backup in that interval
    // while also having sufficiently few backups that the bucket query is fast.
    const start = new Date(end.valueOf() - 24 * 60 * 60 * 1000);
    bootstrappingDumpConfig = {
      bucket: bootstrapBucketSpec,
      cluster: bootstrappingConfig.cluster,
      start,
      end,
    };
  }

  configureForwardAll(
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>
  );
  const sv1 = await installSvc(auth0Client, topupConfig);

  // TODO(#8761) install the validator once the upgrade supports it
  const installNonSvComponents =
    !globalDomainUpgradeConfig.isUpgrade() && globalDomainUpgradeConfig.isDefaultActive();
  const nonSvComponentsDependencies = [sv1.founder.scan];
  const validator = installNonSvComponents
    ? await installValidator1(
        auth0Client,
        'validator1',
        validator1Onboarding.secret,
        'auth0|63e3d75ff4114d87a2c1e4f5',
        splitPostgresInstances,
        globalDomainUpgradeConfig.activeGlobalDomainId,
        nonSvComponentsDependencies,
        backupConfig,
        bootstrappingDumpConfig,
        // x10 validator1's traffic targetThroughput for load tester -- see #9064
        { ...topupConfig, targetThroughput: topupConfig.targetThroughput * 10 }
      )
    : undefined;

  // TODO(#8761) install splitwell once the upgrade supports it
  const splitwell = installNonSvComponents
    ? await installSplitwell(
        auth0Client,
        'auth0|63e12e0415ad881ffe914e61',
        splitwellOnboarding.secret,
        splitPostgresInstances,
        globalDomainUpgradeConfig.activeGlobalDomainId,
        nonSvComponentsDependencies,
        backupConfig,
        bootstrappingDumpConfig,
        topupConfig
      )
    : undefined;

  const docs = installDocs();

  installCNHelmChartByNamespaceName(
    'cluster-ingress',
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>,
    'cluster-ingress',
    'cn-cluster-ingress-full',
    {},
    { dependsOn: [validator, splitwell, docs].flatMap(value => (value ? [value] : [])) }
  );
}

async function installSvc(auth0Client: Auth0Client, topupConfig: ValidatorTopupConfig) {
  const sv1 = await installSvNode(
    {
      auth0Client,
      nodename: 'sv-1',
      onboardingName: isDevNet ? 'Canton-Foundation-1' : 'Canton-Foundation',
      validatorWalletUser: isDevNet
        ? 'auth0|64afbc0956a97fe9577249d7'
        : 'auth0|64529b128448ded6aa68048f',
      onboarding: { type: 'found-collective' },
      approvedSvIdentities,
      expectedValidatorOnboardings: [
        splitwellOnboarding,
        validator1Onboarding,
        standaloneValidatorOnboarding,
      ],
      isDevNet,
      backupConfig,
      bootstrappingDumpConfig,
      topupConfig,
      auth0ValidatorAppName: 'sv1_validator',
      splitPostgresInstances,
      sequencerPruningConfig,
    },
    globalDomainUpgradeConfig
  );
  const svFounderSvApp = sv1.svApp;

  const allSvs = [sv1];

  if (!singleSv) {
    allSvs.push(
      await installSvNode(
        {
          auth0Client,
          nodename: 'sv-2',
          onboardingName: 'Canton-Foundation-2',
          validatorWalletUser: 'auth0|64afbc353bbc7ca776e27bf4',
          onboarding: joinViaSv1(svFounderSvApp, sv2Key),
          approvedSvIdentities,
          expectedValidatorOnboardings: [],
          isDevNet,
          backupConfig: backupConfig,
          auth0ValidatorAppName: 'sv2_validator',
          bootstrappingDumpConfig,
          topupConfig,
          splitPostgresInstances,
          sequencerPruningConfig,
        },
        globalDomainUpgradeConfig,
        svFounderSvApp
      )
    );
    allSvs.push(
      await installSvNode(
        {
          auth0Client,
          nodename: 'sv-3',
          onboardingName: 'Canton-Foundation-3',
          validatorWalletUser: 'auth0|64afbc4431b562edb8995da6',
          onboarding: joinViaSv1(svFounderSvApp, sv3Key),
          approvedSvIdentities,
          expectedValidatorOnboardings: [],
          isDevNet,
          backupConfig,
          auth0ValidatorAppName: 'sv3_validator',
          bootstrappingDumpConfig,
          topupConfig,
          splitPostgresInstances,
          sequencerPruningConfig,
        },
        globalDomainUpgradeConfig,
        svFounderSvApp
      )
    );
    allSvs.push(
      await installSvNode(
        {
          auth0Client,
          nodename: 'sv-4',
          onboardingName: 'Canton-Foundation-4',
          validatorWalletUser: 'auth0|64afbc720e20777e46fff490',
          onboarding: joinViaSv1(svFounderSvApp, sv4Key),
          approvedSvIdentities,
          expectedValidatorOnboardings: [],
          isDevNet,
          backupConfig,
          auth0ValidatorAppName: 'sv4_validator',
          bootstrappingDumpConfig,
          topupConfig,
          splitPostgresInstances,
          sequencerPruningConfig,
        },
        globalDomainUpgradeConfig,
        svFounderSvApp
      )
    );
  }
  return {
    founder: sv1,
    allSvs,
  };
}
