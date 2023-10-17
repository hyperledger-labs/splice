import { envFlag, isDevNet, requireEnv } from 'cn-pulumi-common';

export const CLUSTER_BASENAME = requireEnv(
  'GCP_CLUSTER_BASENAME',
  'The cluster in which this chart is being installed'
);
export const TARGET_CLUSTER = requireEnv(
  'TARGET_CLUSTER',
  'the cluster in which the global domain is running'
);
export const SV_NAME = 'DA-Helm-Test-Node';
export const SV_NAMESPACE = 'sv';

export const version = process.env.CHARTS_VERSION;
export const localCharts = version == '' || version == undefined; // Whether to use helm charts generated locally or taken from the artifactory (the latter being for externally released versions)
export const withDomainFees = envFlag('DOMAIN_FEES');

// Default to admin@sv-dev.com (devnet) or admin@sv.com (non devnet) at the sv-test tenant by default
export const validatorWalletUserName = isDevNet
  ? 'auth0|64b16b9ff7a0dfd00ea3704e'
  : 'auth0|64553aa683015a9687d9cc2e';
