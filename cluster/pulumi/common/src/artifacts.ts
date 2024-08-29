import { config } from './config';

export type Repository = {
  dockerImages: string;
  helm: string;
};
export const repositories = {
  google: {
    dockerImages: 'us-central1-docker.pkg.dev/da-cn-shared/cn-images',
    helm: 'oci://us-central1-docker.pkg.dev/da-cn-shared/cn-images',
  },
  artifactory: {
    dockerImages: 'digitalasset-canton-network-docker.jfrog.io/digitalasset',
    helm: 'https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm',
  },
};
export const artifactsRepository = config.optionalEnv('SPLICE_ARTIFACTS_REPOSITORY');
let repository: Repository;
if (artifactsRepository === undefined) {
  repository = repositories.artifactory;
} else if (artifactsRepository === 'google') {
  repository = repositories.google;
} else if (artifactsRepository === 'artifactory') {
  repository = repositories.artifactory;
} else {
  throw new Error(`Unknown artifacts repository: ${artifactsRepository}`);
}

export type CnChartVersion =
  | { type: 'local'; repository: { dockerImages: string } }
  | {
      type: 'remote';
      repository: Repository;
      version: string;
    };

export function parsedVersion(version?: string): CnChartVersion {
  return version && version !== 'local'
    ? {
        type: 'remote',
        version: version,
        repository: repository,
      }
    : { type: 'local', repository: { dockerImages: repositories.google.dockerImages } };
}
