import { spliceEnvConfig } from './envConfig';

export { spliceEnvConfig as config } from './envConfig';

export const splitwellDarPath = 'splice-node/dars/splitwell-current.dar';

export const DeploySvRunbook = spliceEnvConfig.envFlag('SPLICE_DEPLOY_SV_RUNBOOK', false);
export const DeployValidatorRunbook = spliceEnvConfig.envFlag(
  'SPLICE_DEPLOY_VALIDATOR_RUNBOOK',
  false
);

export const clusterProdLike = spliceEnvConfig.envFlag('GCP_CLUSTER_PROD_LIKE');

// During development we often overwrite the same tag so we use imagePullPolicy: Always.
// Outside of development, we use the default which corresponds to IfNotPresent
// (unless the tag is LATEST which it never is in our setup).
export const imagePullPolicy = clusterProdLike ? {} : { imagePullPolicy: 'Always' };

export const supportsSvRunbookReset = spliceEnvConfig.envFlag('SUPPORTS_SV_RUNBOOK_RESET', false);

export const isMainNet = spliceEnvConfig.envFlag('IS_MAINNET', false);
export const isDevNet = spliceEnvConfig.envFlag('IS_DEVNET', true) && !isMainNet;
export const clusterSmallDisk = spliceEnvConfig.envFlag('CLUSTER_SMALL_DISK', false);
export const publicPrometheusRemoteWrite = spliceEnvConfig.envFlag(
  'PUBLIC_PROMETHEUS_REMOTE_WRITE',
  false
);
