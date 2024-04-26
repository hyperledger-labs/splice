import { config } from 'cn-pulumi-common';

export const CLUSTER_BASENAME = config.requireEnv(
  'GCP_CLUSTER_BASENAME',
  'The cluster in which this chart is being installed'
);

export const VALIDATOR_NAMESPACE = config.optionalEnv('VALIDATOR_NAMESPACE') || 'validator';

export const VALIDATOR_PARTY_HINT = config.optionalEnv('VALIDATOR_PARTY_HINT');
export const VALIDATOR_MIGRATE_PARTY = config.envFlag('VALIDATOR_MIGRATE_PARTY', false);

export const VALIDATOR_NEW_PARTICIPANT_ID = config.envFlag('VALIDATOR_NEW_PARTICIPANT_ID');
