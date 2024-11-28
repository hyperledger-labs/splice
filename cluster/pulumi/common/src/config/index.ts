import { spliceEnvConfig } from './envConfig';

export { spliceEnvConfig as config } from './envConfig';

export const splitwellDarPath = 'splice-node/dars/splitwell-current.dar';

export const DeploySvRunbook = spliceEnvConfig.envFlag('SPLICE_DEPLOY_SV_RUNBOOK', false);

export const artifactsRepository = spliceEnvConfig.optionalEnv('SPLICE_ARTIFACTS_REPOSITORY');

export const dockerImageArtifactsRepository = spliceEnvConfig.optionalEnv(
  'SPLICE_DOCKER_IMAGE_ARTIFACTS_REPOSITORY'
);

// This flag determines whether to split postgres instances per app, or have one per namespace.
// By default, we split instances on CloudSQL (where we expect longer-living environments, thus want to support backup&recovery),
// but not on k8s-deployed postgres (where we optimize for faster deployment).
// One can force splitting them by setting SPLIT_POSTGRES_INSTANCES to true.
export const SplitPostgresInstances =
  spliceEnvConfig.envFlag('SPLIT_POSTGRES_INSTANCES') ||
  spliceEnvConfig.envFlag('ENABLE_CLOUD_SQL');

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
