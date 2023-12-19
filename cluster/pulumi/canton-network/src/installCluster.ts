import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  ValidatorTopupConfig,
  domainFeesConfig,
  envFlag,
  InfrastructureOutputs,
  REPO_ROOT,
  infraStack,
  bootstrapDataBucketSpec,
  isDevNet,
  loadYamlFromFile,
  svKeyFromSecret,
  SvIdKey,
  CnInput,
  sequencerPruningConfig,
} from 'cn-pulumi-common';

import { installDocs } from './docs';
import { configureForwardAll } from './gateway';
import { installClusterIngress } from './ingress';
import { Postgres } from './postgres';
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

function joinViaSv1(
  sv1: pulumi.Resource,
  keys: CnInput<SvIdKey>,
  sequencerDatabase: Postgres
): SvOnboarding {
  return {
    type: 'join-with-key',
    sponsorApiUrl: 'http://sv-app.sv-1:5014',
    sponsorRelease: sv1,
    sequencerDatabase,
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

const bootstrapBucketSpec = bootstrapDataBucketSpec('da-cn-devnet', 'da-cn-data-dumps');

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

export async function installCluster(auth0Client: Auth0Client): Promise<void> {
  configureForwardAll(
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>
  );

  const { svApp: sv1, sequencerPostgres: postgresDB1 } = await installSvNode({
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
    splitPostgresInstances: false,
    sequencerPruningConfig,
  });

  if (!singleSv) {
    await installSvNode(
      {
        auth0Client,
        nodename: 'sv-2',
        onboardingName: 'Canton-Foundation-2',
        validatorWalletUser: 'auth0|64afbc353bbc7ca776e27bf4',
        onboarding: joinViaSv1(sv1, sv2Key, postgresDB1),
        approvedSvIdentities,
        expectedValidatorOnboardings: [],
        isDevNet,
        backupConfig: backupConfig,
        auth0ValidatorAppName: 'sv2_validator',
        bootstrappingDumpConfig,
        topupConfig,
        splitPostgresInstances: false,
        sequencerPruningConfig,
      },
      sv1
    );
    await installSvNode(
      {
        auth0Client,
        nodename: 'sv-3',
        onboardingName: 'Canton-Foundation-3',
        validatorWalletUser: 'auth0|64afbc4431b562edb8995da6',
        onboarding: joinViaSv1(sv1, sv3Key, postgresDB1),
        approvedSvIdentities,
        expectedValidatorOnboardings: [],
        isDevNet,
        backupConfig,
        auth0ValidatorAppName: 'sv3_validator',
        bootstrappingDumpConfig,
        topupConfig,
        splitPostgresInstances: true,
        sequencerPruningConfig,
      },
      sv1
    );
    await installSvNode(
      {
        auth0Client,
        nodename: 'sv-4',
        onboardingName: 'Canton-Foundation-4',
        validatorWalletUser: 'auth0|64afbc720e20777e46fff490',
        onboarding: joinViaSv1(sv1, sv4Key, postgresDB1),
        approvedSvIdentities,
        expectedValidatorOnboardings: [],
        isDevNet,
        backupConfig,
        auth0ValidatorAppName: 'sv4_validator',
        bootstrappingDumpConfig,
        topupConfig,
        splitPostgresInstances: false,
        sequencerPruningConfig,
      },
      sv1
    );
  }

  const validator = await installValidator1(
    auth0Client,
    sv1,
    'validator1',
    validator1Onboarding.secret,
    'auth0|63e3d75ff4114d87a2c1e4f5',
    backupConfig,
    bootstrappingDumpConfig,
    topupConfig
  );

  const splitwell = await installSplitwell(
    auth0Client,
    sv1,
    'auth0|63e12e0415ad881ffe914e61',
    splitwellOnboarding.secret,
    backupConfig,
    bootstrappingDumpConfig,
    topupConfig
  );

  const docs = installDocs();

  installClusterIngress(
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>,
    validator,
    splitwell,
    docs
  );
}
