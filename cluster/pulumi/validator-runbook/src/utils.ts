import { requireEnv } from 'cn-pulumi-common';

export const CLUSTER_BASENAME = requireEnv(
  'GCP_CLUSTER_BASENAME',
  'The cluster in which this chart is being installed'
);

export const VALIDATOR_NAMESPACE = process.env.VALIDATOR_NAMESPACE || 'validator';
