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
import { exactNamespace, requiredEnv } from './utils';

// Note that for now this assumes the entire cluster is under this scripts's control,
// i.e. it was only initialized with the `infrastructure` pulumi, no other `cncluster` scripts (specifically, no other secrets or namespaces created).

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

// Copied from ${REPO_ROOT}/apps/app/src/pack/examples/sv/sv-onboarding.conf
// TODO(#4443): make sure it's OK to reuse these once automated
const SV_NAME = 'DA-Test-Node';
const SV_PUBLIC_KEY =
  'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7uz+zW1YcPJIl+TKqXv6/dfxcx+3ISVFgP6m2saeQ0l6r2lNW+WLfq+HUMcycxX9t6bUJ5kyEebYyfk9JW18KA==';
const SV_PRIVATE_KEY =
  'MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgdRTS3iLr8rPFaLUBbVcu8qYxklmMzQo/4UXcULYESm2hRANCAATu7P7NbVhw8kiX5Mqpe/r91/FzH7chJUWA/qbaxp5DSXqvaU1b5Yt+r4dQxzJzFf23ptQnmTIR5tjJ+T0lbXwo';

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

const participant = installCNHelmChart(
  svNamespace,
  'participant',
  'cn-participant',
  // TODO(#4384): move these values into a file and distribute it with the release
  {
    postgres: 'postgres',
    globalDomain: {
      alias: 'global',
      url: `http://${TARGET_CLUSTER}.network.canton.global:5008`,
    },
    auth: {
      jwksEndpoint: `https://${AUTH0_DOMAIN}/.well-known/jwks.json`,
      // TODO(#4552): support arbitrary audience
      targetAudience: 'https://canton.network.global',
    },
  },
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

const validator = installCNHelmChart(
  svNamespace,
  'validator',
  'cn-validator',
  // TODO(#4384): move these values into a file and distribute it with the release
  {
    participantAddress: 'participant',
    svSponsorAddress: `https://sv.sv-1.svc.${TARGET_CLUSTER}.network.canton.global/api/v0/sv`,
    scanPort: '5012',
    scanAddress: `https://${TARGET_CLUSTER}.network.canton.global`,
    validatorWalletUser: SV_WALLET_USER_ID,
    clusterUrl: `${TARGET_CLUSTER}.network.canton.global`,
    auth: {
      // TODO(#4552): support arbitrary audience
      audience: 'https://canton.network.global',
      jwksUrl: `https://${AUTH0_DOMAIN}/.well-known/jwks.json`,
    },
  },
  localCharts,
  version,
  svImagePullDeps.concat([participant]).concat(svValidatorSecrets(svNamespace))
);

const sv = installCNHelmChart(
  svNamespace,
  'sv-1',
  'cn-sv-node',
  // TODO(#4384): move these values into a file and distribute it with the release
  {
    joinWithKeyOnboarding: {
      sponsorApiUrl: `https://sv.sv-1.svc.${TARGET_CLUSTER}.network.canton.global/api/v0/sv`,
      svcApiAddress: `${TARGET_CLUSTER}.network.canton.global`,
      publicKey: SV_PUBLIC_KEY,
      privateKey: SV_PRIVATE_KEY,
    },
    onboardingName: SV_NAME,
    auth: {
      // TODO(#4552): support arbitrary audience
      audience: 'https://canton.network.global',
      jwksUrl: `https://${AUTH0_DOMAIN}/.well-known/jwks.json`,
    },
  },
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
  // TODO(#4384): move these values into a file and distribute it with the release
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
