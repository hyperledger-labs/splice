import { spliceEnvConfig } from './config/envConfig';

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

export type CnChartVersion =
  | { type: 'local'; repository: { dockerImages: string } }
  | {
      type: 'remote';
      repository: Repository;
      version: string;
    };

export function parsedVersion(
  version: string | undefined,
  repositoryValue?: string
): CnChartVersion {
  return version && version.length > 0 && version !== 'local'
    ? {
        type: 'remote',
        version: version,
        repository: repository(repositoryValue),
      }
    : { type: 'local', repository: { dockerImages: repositories.google.dockerImages } };
}

function repository(repositoryValue?: string) {
  if (repositoryValue === undefined) {
    return repositories.artifactory;
  } else if (repositoryValue === 'google') {
    return repositories.google;
  } else if (repositoryValue === 'artifactory') {
    return repositories.artifactory;
  } else {
    throw new Error(`Unknown artifacts repository: ${repositoryValue}`);
  }
}

export const CHARTS_VERSION: string | undefined = spliceEnvConfig.optionalEnv('CHARTS_VERSION');
