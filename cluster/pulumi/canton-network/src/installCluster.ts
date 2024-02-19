import {
  Auth0Client,
  BackupConfig,
  bootstrapDataBucketSpec,
  BootstrappingDumpConfig,
  domainFeesConfig,
  envFlag,
  isDevNet,
  requireEnv,
  sequencerPruningConfig,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';

import { installChaosMesh } from './chaosMesh';
import { installDocs } from './docs';
import { GlobalDomainUpgradeConfig } from './globalDomainNode';
import { installSplitwell } from './splitwell';
import { Svc } from './svc';
import svconfs from './svconfs';
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

// This flag determines whether to split postgres instances per app, or have one per namespace.
// By default, we split instances on CloudSQL (where we expect longer-living environments, thus want to support backup&recovery),
// but not on k8s-deployed postgres (where we optimize for faster deployment).
// One can force splitting them by setting SPLIT_POSTGRES_INSTANCES to true.
const splitPostgresInstances = envFlag('SPLIT_POSTGRES_INSTANCES') || envFlag('ENABLE_CLOUD_SQL');

const enableChaosMesh = envFlag('ENABLE_CHAOS_MESH');

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = process.env.BOOTSTRAPPING_CONFIG
  ? JSON.parse(process.env.BOOTSTRAPPING_CONFIG)
  : undefined;

const globalDomainUpgradeConfig: GlobalDomainUpgradeConfig = GlobalDomainUpgradeConfig.fromEnv();

const installNonSvComponents = envFlag(
  'CN_NON_SV_NODES',
  !globalDomainUpgradeConfig.containsUpgrade() && globalDomainUpgradeConfig.isDefaultActive()
);

const svRunbookApprovedSvIdentities = [
  {
    name: 'DA-Helm-Test-Node',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==',
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

const standaloneValidatorOnboarding = {
  name: 'validator',
  secret: 'validatorsecret',
  expiresIn: '24h',
};

let backupConfig: BackupConfig | undefined;
let bootstrappingDumpConfig: BootstrappingDumpConfig | undefined;

function getSvcSize(): number {
  // If not devnet, enforce 1 sv
  if (!isDevNet) {
    return 1;
  }

  const maxSvcSize = svconfs.length;
  const svcSize = +requireEnv(
    'SVC_SIZE',
    `Specify how many foundation SV nodes this cluster should be deployed with. (min 1, max ${maxSvcSize})`
  );

  if (svcSize < 1) {
    throw new Error('SVC_SIZE must be at least 1');
  }

  if (svcSize > maxSvcSize) {
    throw new Error(`SVC_SIZE must be at most ${maxSvcSize}`);
  }

  return svcSize;
}

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

  const svc = new Svc('svc', {
    svcSize: getSvcSize(),

    auth0Client,
    approvedSvIdentities: approveSvRunbook ? svRunbookApprovedSvIdentities : [],
    expectedValidatorOnboardings: [
      splitwellOnboarding,
      validator1Onboarding,
      standaloneValidatorOnboarding,
    ],
    isDevNet,
    backupConfig,
    bootstrappingDumpConfig,
    topupConfig,
    splitPostgresInstances,
    sequencerPruningConfig,
    globalDomainUpgradeConfig,
  });

  const allSvs = await svc.allSvs;

  const svDependencies = allSvs.flatMap(sv => [sv.scan, sv.svApp, sv.validatorApp]);

  // TODO(#8761) install the non sv nodes once the upgrade supports it
  const nonSvComponentsDependencies = allSvs.map(sv => sv.scan);
  if (installNonSvComponents) {
    await installValidator1(
      auth0Client,
      'validator1',
      validator1Onboarding.secret,
      'auth0|63e3d75ff4114d87a2c1e4f5',
      splitPostgresInstances,
      globalDomainUpgradeConfig.activeMigrationId,
      nonSvComponentsDependencies,
      backupConfig,
      bootstrappingDumpConfig,
      // x10 validator1's traffic targetThroughput for load tester -- see #9064
      { ...topupConfig, targetThroughput: topupConfig.targetThroughput * 10 }
    );

    await installSplitwell(
      auth0Client,
      'auth0|63e12e0415ad881ffe914e61',
      splitwellOnboarding.secret,
      splitPostgresInstances,
      globalDomainUpgradeConfig.activeMigrationId,
      nonSvComponentsDependencies,
      backupConfig,
      bootstrappingDumpConfig,
      topupConfig
    );
  }

  installDocs();

  if (enableChaosMesh) {
    installChaosMesh({ dependsOn: svDependencies });
  }
}
