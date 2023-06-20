import { requireEnv } from 'cn-pulumi-common';

export const CLUSTER_BASENAME = requireEnv(
  'GCP_CLUSTER_BASENAME',
  'The cluster in which this chart is being installed'
);
export const REPO_ROOT = requireEnv('REPO_ROOT', 'root directory of the repo');
export const TARGET_CLUSTER = requireEnv(
  'TARGET_CLUSTER',
  'the cluster in which the global domain is running'
);
export const SV_NAME = 'DA-Helm-Test-Node';
