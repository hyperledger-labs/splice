import * as pulumi from '@pulumi/pulumi';

import { installCNHelmChart, installCNHelmChartByNamespaceName } from './helm';
import { installLoopback } from './loopback';
import {
  /*configureSecrets, */
  directoryUserParticipantSecret,
  imagePullSecret,
  imagePullSecretByNamespaceName,
  scanUserParticipantSecret,
  sv1UserParticipantSecret,
  sv1UserValidatorParticipantSecret,
  svAppSecrets,
  svKeySecret,
  svValidatorSecrets,
  svcUserParticipantSecret,
} from './secrets';
import { exactNamespace, requiredEnv, loadYamlFromFile } from './utils';

// Note that for now this assumes the entire cluster is under this scripts's control,
// i.e. it was only initialized with the `infrastructure` pulumi, no other `cncluster` scripts (specifically, no other secrets or namespaces created).

const REPO_ROOT = requiredEnv('REPO_ROOT', 'root directory of the repo');
const version = process.env.CHARTS_VERSION;
const localCharts = version == '' || version == undefined; // Whether to use helm charts generated locally or taken from the artifactory (the latter being for externally released versions)
const CLUSTER_BASENAME = requiredEnv(
  'GCP_CLUSTER_BASENAME',
  'The cluster in which this chart is being installed'
);
const AUTH0_DOMAIN = requiredEnv('AUTH0_DOMAIN', 'the Auth0 tenant domain'); // auth0 plugin requires this to be defined anyway, so we just reuse that
const TARGET_CLUSTER = requiredEnv(
  'TARGET_CLUSTER',
  'the cluster in which the global domain is running'
);
const SV_WALLET_USER_ID = process.env.SV_WALLET_USER_ID || 'auth0|64553aa683015a9687d9cc2e'; // Default to admin@sv.com at the sv-test tenant by default
const SV_NAMESPACE = process.env.SV_NAMESPACE || 'sv';

console.error(
  localCharts
    ? 'Using locally built charts'
    : `Using charts from the artifactory, version ${version}`
);
console.error(`TARGET_CLUSTER: ${TARGET_CLUSTER}`);
console.error(`Installing SV node in namespace: ${SV_NAMESPACE}`);

const SV_NAME = 'DA-Helm-Test-Node';
const SV_PUBLIC_KEY =
  'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==';
const SV_PRIVATE_KEY =
  'MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBsFuFa7Eumkdg4dcf/vxIXgAje2ULVz+qTKP3s/tHqKw==';

const svNamespace = exactNamespace(SV_NAMESPACE, {
  'istio-injection': 'enabled',
});

const loopback = installLoopback(svNamespace, CLUSTER_BASENAME, localCharts, version);

const svImagePullDeps = localCharts ? [] : imagePullSecret(svNamespace);

const postgres = installCNHelmChart(
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

const participant = installCNHelmChart(
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

// TODO(#5202): align YOUR_ISTANCE_NAME vs OIDC_AUTHORITY_URL
const validatorValues = loadYamlFromFile(
  `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`,
  {
    TARGET_CLUSTER: TARGET_CLUSTER,
    SV_WALLET_USER_ID: SV_WALLET_USER_ID,
    YOUR_INSTANCE_NAME: AUTH0_DOMAIN.replaceAll('.us.auth0.com', ''),
  }
);

const validator = installCNHelmChart(
  svNamespace,
  'validator',
  'cn-validator',
  validatorValues,
  localCharts,
  version,
  svImagePullDeps.concat([participant]).concat(svValidatorSecrets(svNamespace))
);

const svValues = loadYamlFromFile(
  `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-values.yaml`,
  {
    TARGET_CLUSTER: TARGET_CLUSTER,
    YOUR_SV_NAME: SV_NAME,
    YOUR_INSTANCE_NAME: AUTH0_DOMAIN.replaceAll('.us.auth0.com', ''),
  }
);

const sv = installCNHelmChart(
  svNamespace,
  'sv-1',
  'cn-sv-node',
  svValues,
  localCharts,
  version,
  svImagePullDeps.concat([validator, participant]).concat(svAppSecrets(svNamespace))
);

const infraStack = new pulumi.StackReference(`infra.${CLUSTER_BASENAME}`);

const ingressImagePullDeps = localCharts ? [] : imagePullSecretByNamespaceName('cluster-ingress');
installCNHelmChartByNamespaceName(
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
