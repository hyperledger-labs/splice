import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import {
  Auth0Client,
  BackupConfig,
  ChartValues,
  exactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  fixedTokens,
  GcpBucketConfig,
  infraStack,
  installGcpBucket,
  installGcpBucketSecret,
  loadYamlFromFile,
  participantBootstrapDumpSecretName,
  readAndInstallParticipantBootstrapDump,
} from 'cn-pulumi-common';
import { exit } from 'process';

import { auth0Cfg } from './auth0cfg';
import { installCometBftNode } from './cometbft';
import { installCNSVHelmChart, installCNSVHelmChartByNamespaceName } from './helm';
import { installLoopback } from './loopback';
import {
  svAppSecrets,
  svValidatorSecrets,
  svDirectoryUiSecret,
  imagePullSecret,
  imagePullSecretByNamespaceName,
  svKeySecret,
} from './secrets';
import { CLUSTER_BASENAME, TARGET_CLUSTER, REPO_ROOT, SV_NAME } from './utils';

const isDevNet = process.env.NON_DEVNET === undefined || process.env.NON_DEVNET === '';
if (!isDevNet) {
  console.error('Launching in non-devnet mode');
}

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = process.env.BOOTSTRAPPING_CONFIG
  ? JSON.parse(process.env.BOOTSTRAPPING_CONFIG)
  : undefined;

const participantIdentitiesFile = process.env.PARTICIPANT_IDENTITIES_FILE;

const backupBucketConfig: GcpBucketConfig = {
  projectId: 'da-cn-devnet',
  bucketName: 'da-cn-data-dumps',
};

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  const version = process.env.CHARTS_VERSION;
  const localCharts = version == '' || version == undefined; // Whether to use helm charts generated locally or taken from the artifactory (the latter being for externally released versions)
  const SV_WALLET_USER_ID =
    process.env.SV_WALLET_USER_ID ||
    (isDevNet ? 'auth0|64b16b9ff7a0dfd00ea3704e' : 'auth0|64553aa683015a9687d9cc2e'); // Default to admin@sv-dev.com (devnet) or admin@sv.com (non devnet) at the sv-test tenant by default
  const SV_NAMESPACE = process.env.SV_NAMESPACE || 'sv';
  const DEFAULT_AUDIENCE = 'https://canton.network.global';
  const withDomainFees = process.env.DOMAIN_FEES !== undefined && process.env.DOMAIN_FEES !== '';

  console.error(
    localCharts
      ? 'Using locally built charts'
      : `Using charts from the artifactory, version ${version}`
  );
  console.error(`TARGET_CLUSTER: ${TARGET_CLUSTER}`);
  console.error(`Installing SV node in namespace: ${SV_NAMESPACE}`);

  const SV_PUBLIC_KEY =
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==';
  const SV_PRIVATE_KEY =
    'MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBsFuFa7Eumkdg4dcf/vxIXgAje2ULVz+qTKP3s/tHqKw==';

  const svNamespace = exactNamespace(SV_NAMESPACE, {
    'istio-injection': 'enabled',
  });

  const loopback =
    TARGET_CLUSTER === CLUSTER_BASENAME
      ? installLoopback(svNamespace, CLUSTER_BASENAME, localCharts, version)
      : null;

  const svImagePullDeps = localCharts ? [] : imagePullSecret(svNamespace);

  const password = new random.RandomPassword(`${svNamespace.logicalName}-postgres-passwd`, {
    length: 16,
    overrideSpecial: '_%@',
    special: true,
  }).result;

  if (participantIdentitiesFile && bootstrappingConfig) {
    console.error(
      `We can restore participant identities from *either* a file or from GCP,` +
        `but both PARTICIPANT_IDENTITIES_FILE and BOOTSTRAPPING_CONFIG have been set.`
    );
    exit(1);
  } else if (participantIdentitiesFile) {
    console.error(`Bootstrapping participant identity from file ${participantIdentitiesFile}`);
  } else if (bootstrappingConfig) {
    console.error(`Bootstrapping participant identity from cluster ${bootstrappingConfig.cluster}`);
  } else {
    console.error(`Bootstraping participant with fresh identity`);
  }

  let participantBootstrapDumpSecret: pulumi.Resource | undefined;
  let backupConfigSecret: pulumi.Resource | undefined;
  let backupConfig: BackupConfig | undefined;

  if (participantIdentitiesFile) {
    participantBootstrapDumpSecret = await readAndInstallParticipantBootstrapDump(
      svNamespace,
      participantIdentitiesFile
    );
  } else if (bootstrappingConfig) {
    const backupBucket = installGcpBucket(backupBucketConfig);
    backupConfig = {
      prefix: `${CLUSTER_BASENAME}/${SV_NAMESPACE}`,
      backupInterval: '10m',
      bucket: backupBucket,
    };
    const end = new Date(Date.parse(bootstrappingConfig.date));
    // We search within an interval of 2 hours. Given that we usually backups every 10min, this gives us
    // more than enough of a threshold to make sure each node has one backup in that interval
    // while also having sufficiently few backups that the bucket query is fast.
    const start = new Date(end.valueOf() - 2 * 60 * 60 * 1000);
    const bootstrappingDumpConfig = {
      bucket: backupBucket,
      cluster: bootstrappingConfig.cluster,
      start,
      end,
    };
    participantBootstrapDumpSecret = fetchAndInstallParticipantBootstrapDump(
      svNamespace,
      bootstrappingDumpConfig
    );
    backupConfigSecret = installGcpBucketSecret(svNamespace, backupConfig.bucket);
  }

  const postgres = installCNSVHelmChart(
    svNamespace,
    'postgres',
    'cn-postgres',
    {
      postgresPassword: password,
    },
    localCharts,
    version
  );

  const participantValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`, {
      TARGET_CLUSTER: TARGET_CLUSTER,
      OIDC_AUTHORITY_URL: auth0Cfg.auth0Domain,
    }),
    postgresPassword: password,
    disableAutoInit: !!participantBootstrapDumpSecret,
  };

  const participantValuesWithSpecifiedAud: ChartValues = {
    ...participantValues,
    auth: {
      ...participantValues.auth,
      targetAudience: auth0Cfg.appToApiAudience['participant'] || DEFAULT_AUDIENCE,
    },
  };

  const { appSecret: svAppSecret, uiSecret: svAppUISecret } = await svAppSecrets(
    svNamespace,
    auth0Client
  );

  const participant = installCNSVHelmChart(
    svNamespace,
    'participant',
    'cn-participant',
    participantValuesWithSpecifiedAud,
    localCharts,
    version,
    svImagePullDeps
      .concat([postgres, svAppSecret, svKeySecret(svNamespace, SV_PUBLIC_KEY, SV_PRIVATE_KEY)])
      .concat(loopback !== null ? loopback : [])
  );

  const svValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-values.yaml`, {
      TARGET_CLUSTER: TARGET_CLUSTER,
      YOUR_SV_NAME: SV_NAME,
      OIDC_AUTHORITY_URL: auth0Cfg.auth0Domain,
      'Digital-Asset': isDevNet ? 'Canton-Foundation-2' : 'Digital-Asset',
    }),
    participantBootstrappingDump: participantBootstrapDumpSecret
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
  };

  const svValuesWithSpecifiedAud: ChartValues = {
    ...svValues,
    auth: {
      ...svValues.auth,
      audience: auth0Cfg.appToApiAudience['sv'] || DEFAULT_AUDIENCE,
    },
  };

  const fixedTokensValue: ChartValues = {
    cluster: {
      fixedTokens: true,
    },
  };

  const svValuesWithFixedTokens = {
    ...svValuesWithSpecifiedAud,
    ...fixedTokensValue,
  };

  const sv = installCNSVHelmChart(
    svNamespace,
    'sv-app',
    'cn-sv-node',
    fixedTokens() ? svValuesWithFixedTokens : svValuesWithSpecifiedAud,
    localCharts,
    version,
    svImagePullDeps
      .concat([participant])
      .concat([svAppSecret, svAppUISecret])
      .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
  );

  installCNSVHelmChart(
    svNamespace,
    'scan',
    'cn-scan',
    fixedTokens() ? fixedTokensValue : {},
    localCharts,
    version,
    svImagePullDeps.concat([sv, participant]).concat(svAppSecret)
  );

  const validatorValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`, {
      TARGET_CLUSTER: TARGET_CLUSTER,
      SV_WALLET_USER_ID: SV_WALLET_USER_ID,
      OIDC_AUTHORITY_URL: auth0Cfg.auth0Domain,
    }),
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-validator-values.yaml`),
    participantIdentitiesBackup: backupConfig,
  };

  const validatorValuesWithSpecifiedAud: ChartValues = {
    ...validatorValues,
    auth: {
      ...validatorValues.auth,
      audience: auth0Cfg.appToApiAudience['validator'] || DEFAULT_AUDIENCE,
      ledgerApiAudience: auth0Cfg.appToApiAudience['participant'] || DEFAULT_AUDIENCE,
    },
  };

  const validatorValuesWithMaybeFixedTokens: ChartValues = {
    ...validatorValuesWithSpecifiedAud,
    ...(fixedTokens() ? fixedTokensValue : {}),
  };

  const validatorValuesWithMaybeDomainFees = validatorValuesWithMaybeFixedTokens;
  if (!withDomainFees) {
    validatorValuesWithMaybeDomainFees['topup']['enabled'] = false;
  }

  const { appSecret: svValidatorAppSecret, uiSecret: svValidatorUISecret } =
    await svValidatorSecrets(svNamespace, auth0Client);

  const validator = installCNSVHelmChart(
    svNamespace,
    'validator',
    'cn-validator',
    validatorValuesWithMaybeDomainFees,
    localCharts,
    version,
    svImagePullDeps
      .concat([sv, participant])
      .concat([svValidatorAppSecret, svValidatorUISecret])
      .concat([svDirectoryUiSecret(svNamespace, auth0Client)])
      .concat(backupConfigSecret ? [backupConfigSecret] : [])
  );

  const ingressImagePullDeps = localCharts ? [] : imagePullSecretByNamespaceName('cluster-ingress');
  installCNSVHelmChartByNamespaceName(
    infraStack.requireOutput('ingressNs') as pulumi.Output<string>,
    'cluster-ingress-sv',
    'cn-cluster-ingress-sv',
    {
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: SV_NAMESPACE,
      },
    },
    localCharts,
    version,
    ingressImagePullDeps.concat([sv, validator])
  );

  installCometBftNode(svNamespace);
}
