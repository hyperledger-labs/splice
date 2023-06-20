import * as pulumi from '@pulumi/pulumi';
import { Auth0Client, ChartValues, fixedTokens, infraStack } from 'cn-pulumi-common';

import { auth0Cfg } from './auth0cfg';
import { installCometBftNode } from './cometbft';
import { installCNSVHelmChart, installCNSVHelmChartByNamespaceName } from './helm';
import { installLoopback } from './loopback';
import {
  createSvAppSecrets,
  createSvValidatorSecrets,
  createSvDirectoryUiSecrets,
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
  loadYamlFromFile,
  CLUSTER_BASENAME,
  TARGET_CLUSTER,
  REPO_ROOT,
  SV_NAME,
} from './utils';

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  const version = process.env.CHARTS_VERSION;
  const localCharts = version == '' || version == undefined; // Whether to use helm charts generated locally or taken from the artifactory (the latter being for externally released versions)
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
      OIDC_AUTHORITY_URL: auth0Cfg.auth0Domain,
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
      OIDC_AUTHORITY_URL: auth0Cfg.auth0Domain,
    }
  );

  const fixedTokensValue: ChartValues = {
    cluster: {
      fixedTokens: true,
    },
  };

  const svValuesWithFixedTokens = {
    ...svValues,
    ...fixedTokensValue,
  };

  const svAppSecrets = await createSvAppSecrets(svNamespace, auth0Client);

  const sv = installCNSVHelmChart(
    svNamespace,
    'sv-app',
    'cn-sv-node',
    fixedTokens() ? svValuesWithFixedTokens : svValues,
    localCharts,
    version,
    svImagePullDeps.concat([participant]).concat([svAppSecrets.appSecret, svAppSecrets.uiSecret])
  );

  installCNSVHelmChart(
    svNamespace,
    'scan',
    'cn-scan',
    fixedTokens() ? fixedTokensValue : {},
    localCharts,
    version,
    svImagePullDeps.concat([sv, participant]).concat(svAppSecrets.appSecret)
  );

  const validatorValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`,
    {
      TARGET_CLUSTER: TARGET_CLUSTER,
      SV_WALLET_USER_ID: SV_WALLET_USER_ID,
      OIDC_AUTHORITY_URL: auth0Cfg.auth0Domain,
    }
  );

  const validatorValuesWithFixedTokens = {
    ...validatorValues,
    ...fixedTokensValue,
  };

  const svValidatorSecrets = await createSvValidatorSecrets(svNamespace, auth0Client);
  const svDirectoryUiSecrets = createSvDirectoryUiSecrets(svNamespace, auth0Cfg.auth0Domain);

  const validator = installCNSVHelmChart(
    svNamespace,
    'validator',
    'cn-validator',
    fixedTokens() ? validatorValuesWithFixedTokens : validatorValues,
    localCharts,
    version,
    svImagePullDeps
      .concat([sv, participant])
      .concat([svValidatorSecrets.appSecret, svValidatorSecrets.uiSecret])
      .concat([svDirectoryUiSecrets])
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
