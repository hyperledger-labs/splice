import * as pulumi from '@pulumi/pulumi';

import { installCometBftNode } from './cometbft';
import { installCNSVHelmChart, installCNSVHelmChartByNamespaceName } from './helm';
import { installLoopback } from './loopback';
import {
  createSvAppSecrets,
  createSvValidatorSecrets,
  createSvDirectoryUiSecrets,
  /*configureSecrets, */
  directoryUserParticipantSecret,
  imagePullSecret,
  imagePullSecretByNamespaceName,
  scanUserParticipantSecret,
  sv1UserParticipantSecret,
  sv1UserValidatorParticipantSecret,
  svKeySecret,
  svcUserParticipantSecret,
} from './secrets';
import {
  exactNamespace,
  requiredEnv,
  loadYamlFromFile,
  CLUSTER_BASENAME,
  TARGET_CLUSTER,
  REPO_ROOT,
  SV_NAME,
} from './utils';

// Note that for now this assumes the entire cluster is under this scripts's control,
// i.e. it was only initialized with the `infrastructure` pulumi, no other `cncluster` scripts (specifically, no other secrets or namespaces created).

const version = process.env.CHARTS_VERSION;
const localCharts = version == '' || version == undefined; // Whether to use helm charts generated locally or taken from the artifactory (the latter being for externally released versions)
const AUTH0_DOMAIN = requiredEnv('AUTH0_DOMAIN', 'the Auth0 tenant domain'); // auth0 plugin requires this to be defined anyway, so we just reuse that
const SV_WALLET_USER_ID = process.env.SV_WALLET_USER_ID || 'auth0|64553aa683015a9687d9cc2e'; // Default to admin@sv.com at the sv-test tenant by default
const SV_NAMESPACE = process.env.SV_NAMESPACE || 'sv';

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

const loopback = installLoopback(svNamespace, CLUSTER_BASENAME, localCharts, version);

const svImagePullDeps = localCharts ? [] : imagePullSecret(svNamespace);

const postgres = installCNSVHelmChart(
  svNamespace,
  'postgres',
  'cn-postgres',
  {},
  localCharts,
  version
);

const participantValues = loadYamlFromFile(
  `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
  {
    TARGET_CLUSTER: TARGET_CLUSTER,
    OIDC_AUTHORITY_URL: AUTH0_DOMAIN,
  }
);

const participant = installCNSVHelmChart(
  svNamespace,
  'participant',
  'cn-participant',
  participantValues,
  localCharts,
  version,
  svImagePullDeps.concat([
    postgres,
    loopback,
    sv1UserParticipantSecret(svNamespace),
    sv1UserValidatorParticipantSecret(svNamespace),
    scanUserParticipantSecret(svNamespace),
    directoryUserParticipantSecret(svNamespace),
    svcUserParticipantSecret(svNamespace),
    svKeySecret(svNamespace, SV_PUBLIC_KEY, SV_PRIVATE_KEY),
  ])
);

const svValues = loadYamlFromFile(
  `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-values.yaml`,
  {
    TARGET_CLUSTER: TARGET_CLUSTER,
    YOUR_SV_NAME: SV_NAME,
    OIDC_AUTHORITY_URL: AUTH0_DOMAIN,
  }
);

const svAppSecrets = createSvAppSecrets(svNamespace);

const sv = installCNSVHelmChart(
  svNamespace,
  'sv-app',
  'cn-sv-node',
  svValues,
  localCharts,
  version,
  svImagePullDeps.concat([participant]).concat([svAppSecrets.appSecret, svAppSecrets.uiSecret])
);

installCNSVHelmChart(
  svNamespace,
  'scan',
  'cn-scan',
  {},
  localCharts,
  version,
  svImagePullDeps.concat([sv, participant]).concat(svAppSecrets.appSecret)
);

const validatorValues = loadYamlFromFile(
  `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`,
  {
    TARGET_CLUSTER: TARGET_CLUSTER,
    SV_WALLET_USER_ID: SV_WALLET_USER_ID,
    OIDC_AUTHORITY_URL: AUTH0_DOMAIN,
  }
);

const svValidatorSecrets = createSvValidatorSecrets(svNamespace);
const svDirectoryUiSecrets = createSvDirectoryUiSecrets(svNamespace);

const validator = installCNSVHelmChart(
  svNamespace,
  'validator',
  'cn-validator',
  validatorValues,
  localCharts,
  version,
  svImagePullDeps
    .concat([sv, participant])
    .concat([svValidatorSecrets.appSecret, svValidatorSecrets.uiSecret])
    .concat([svDirectoryUiSecrets])
);

const infraStack = new pulumi.StackReference(`infra.${CLUSTER_BASENAME}`);

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
