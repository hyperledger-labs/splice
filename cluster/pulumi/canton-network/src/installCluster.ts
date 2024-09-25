import { Resource } from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  bootstrapDataBucketSpec,
  BootstrappingDumpConfig,
  config,
  isDevNet,
  sequencerPruningConfig,
  svValidatorTopupConfig,
  nonSvValidatorTopupConfig,
  svOnboardingPollingInterval,
  defaultVersion,
  ExpectedValidatorOnboarding,
  preApproveValidatorRunbook,
  ApprovedSvIdentity,
  nonDevNetNonSvValidatorTopupConfig,
  DecentralizedSynchronizerUpgradeConfig,
} from 'splice-pulumi-common';
import { getDsoSize } from 'splice-pulumi-common-sv';

import { installChaosMesh } from './chaosMesh';
import { installDocs } from './docs';
import { Dso } from './dso';
import { installSplitwell } from './splitwell';
import { autoAcceptTransfersConfigFromEnv } from './validator';
import { installValidator1 } from './validator1';

/// Toplevel Chart Installs

console.error(`Launching with isDevNet: ${isDevNet}`);

// This flag determines whether to add a approved SV entry of 'DA-Helm-Test-Node'
// An 'DA-Helm-Test-Node' entry is already added to `approved-sv-id-values-dev.yaml` so it is added by default for devnet deployment.
// This flag is only relevant to non-devnet deployment.
const approveSvRunbook = config.envFlag('APPROVE_SV_RUNBOOK');
if (approveSvRunbook) {
  console.error('Approving SV used in SV runbook');
}

// This flag determines whether to split postgres instances per app, or have one per namespace.
// By default, we split instances on CloudSQL (where we expect longer-living environments, thus want to support backup&recovery),
// but not on k8s-deployed postgres (where we optimize for faster deployment).
// One can force splitting them by setting SPLIT_POSTGRES_INSTANCES to true.
const splitPostgresInstances =
  config.envFlag('SPLIT_POSTGRES_INSTANCES') || config.envFlag('ENABLE_CLOUD_SQL');

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

const disableOnboardingParticipantPromotionDelay = config.envFlag(
  'DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY',
  false
);

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = config.optionalEnv('BOOTSTRAPPING_CONFIG')
  ? JSON.parse(config.requireEnv('BOOTSTRAPPING_CONFIG'))
  : undefined;

const mustInstallValidator1 = config.envFlag('CN_INSTALL_VALIDATOR1', true);

const mustInstallSplitwell = config.envFlag('CN_INSTALL_SPLITWELL', true);

const svRunbookApprovedSvIdentities: ApprovedSvIdentity[] = [
  {
    name: 'DA-Helm-Test-Node',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==',
    rewardWeightBps: 10000,
  },
];

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

const standaloneValidatorOnboarding: ExpectedValidatorOnboarding | undefined =
  preApproveValidatorRunbook
    ? {
        name: 'validator',
        secret: 'validatorsecret',
        expiresIn: '24h',
      }
    : undefined;

let periodicBackupConfig: BackupConfig | undefined;
let bootstrappingDumpConfig: BootstrappingDumpConfig | undefined;

export async function installCluster(
  auth0Client: Auth0Client
): Promise<{ dso: Dso; validator1?: Resource }> {
  console.error(
    defaultVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the artifactory by default, version ${defaultVersion.version}`
  );

  const bootstrapBucketSpec = await bootstrapDataBucketSpec(
    config.optionalEnv('DATA_DUMPS_PROJECT') || 'da-cn-devnet',
    config.optionalEnv('DATA_DUMPS_BUCKET') || 'da-cn-data-dumps'
  );

  if (!isDevNet) {
    periodicBackupConfig = { backupInterval: '10m', location: { bucket: bootstrapBucketSpec } };
  }
  const identitiesBackupLocation = { bucket: bootstrapBucketSpec };

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
  const expectedValidatorOnboardings: ExpectedValidatorOnboarding[] = [];
  if (mustInstallSplitwell) {
    expectedValidatorOnboardings.push(splitwellOnboarding);
  }
  if (mustInstallValidator1) {
    expectedValidatorOnboardings.push(validator1Onboarding);
  }
  if (standaloneValidatorOnboarding) {
    expectedValidatorOnboardings.push(standaloneValidatorOnboarding);
  }

  const dso = new Dso('dso', {
    dsoSize: getDsoSize(),

    auth0Client,
    approvedSvIdentities: approveSvRunbook ? svRunbookApprovedSvIdentities : [],
    expectedValidatorOnboardings,
    isDevNet,
    periodicBackupConfig,
    identitiesBackupLocation,
    bootstrappingDumpConfig,
    topupConfig: svValidatorTopupConfig,
    splitPostgresInstances,
    sequencerPruningConfig,
    decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerUpgradeConfig,
    onboardingPollingInterval: svOnboardingPollingInterval,
    disableOnboardingParticipantPromotionDelay,
  });

  const allSvs = await dso.allSvs;

  const svDependencies = allSvs.flatMap(sv => [sv.scan, sv.svApp, sv.validatorApp, sv.ingress]);

  const nonSvComponentsDependencies = allSvs.flatMap(sv =>
    [sv.scan, sv.svApp, sv.ingress].concat(sv.decentralizedSynchronizer.dependencies)
  );
  let validator1;

  if (mustInstallValidator1) {
    const topupConfig = isDevNet ? nonSvValidatorTopupConfig : nonDevNetNonSvValidatorTopupConfig;
    validator1 = await installValidator1(
      auth0Client,
      'validator1',
      validator1Onboarding.secret,
      'auth0|63e3d75ff4114d87a2c1e4f5',
      splitPostgresInstances,
      DecentralizedSynchronizerUpgradeConfig,
      mustInstallSplitwell,
      nonSvComponentsDependencies,
      periodicBackupConfig,
      bootstrappingDumpConfig,
      {
        ...topupConfig,
        // x10 validator1's traffic targetThroughput for load tester -- see #9064
        targetThroughput: topupConfig.targetThroughput * 10,
      },
      autoAcceptTransfersConfigFromEnv('VALIDATOR1')
    );
  }

  if (mustInstallSplitwell) {
    await installSplitwell(
      auth0Client,
      'auth0|63e12e0415ad881ffe914e61',
      'auth0|65de04b385816c4a38cc044f',
      splitwellOnboarding.secret,
      splitPostgresInstances,
      DecentralizedSynchronizerUpgradeConfig,
      nonSvComponentsDependencies,
      periodicBackupConfig,
      bootstrappingDumpConfig,
      isDevNet ? nonSvValidatorTopupConfig : nonDevNetNonSvValidatorTopupConfig
    );
  }

  installDocs();

  if (enableChaosMesh) {
    installChaosMesh({ dependsOn: svDependencies });
  }

  return {
    dso,
    validator1,
  };
}
