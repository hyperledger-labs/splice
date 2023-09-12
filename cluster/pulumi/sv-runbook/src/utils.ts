import { envFlag, requireEnv } from 'cn-pulumi-common';

export const CLUSTER_BASENAME = requireEnv(
  'GCP_CLUSTER_BASENAME',
  'The cluster in which this chart is being installed'
);
export const TARGET_CLUSTER = requireEnv(
  'TARGET_CLUSTER',
  'the cluster in which the global domain is running'
);
export const SV_NAME = 'DA-Helm-Test-Node';

export const version = process.env.CHARTS_VERSION;
export const localCharts = version == '' || version == undefined; // Whether to use helm charts generated locally or taken from the artifactory (the latter being for externally released versions)
export const withDomainFees = envFlag('DOMAIN_FEES');
export const SV_NAMESPACE = process.env.SV_NAMESPACE || 'sv';
