import { envFlag, requireEnv } from 'cn-pulumi-common';

export const CLUSTER_BASENAME = requireEnv(
  'GCP_CLUSTER_BASENAME',
  'The cluster in which this chart is being installed'
);

export const VALIDATOR_NAMESPACE = process.env.VALIDATOR_NAMESPACE || 'validator';

export const VALIDATOR_PARTY_HINT = process.env.VALIDATOR_PARTY_HINT;
export const VALIDATOR_MIGRATE_PARTY = envFlag('VALIDATOR_MIGRATE_PARTY', false);

export const VALIDATOR_NEW_PARTICIPANT_ID = process.env.VALIDATOR_NEW_PARTICIPANT_ID;
