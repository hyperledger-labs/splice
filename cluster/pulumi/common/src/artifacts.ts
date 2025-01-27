import { spliceEnvConfig } from './config/envConfig';

export type Repository = {
  registry: string;
  dockerImages: string;
  helm: string;
};
const devDockerReg = 'digitalasset-canton-network-docker-dev.jfrog.io';
const pubDockerReg = 'digitalasset-canton-network-docker.jfrog.io';
export const repositories = {
  private: {
    registry: devDockerReg,
    dockerImages: devDockerReg + '/digitalasset',
    helm: 'https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm-dev',
  },
  public: {
    registry: pubDockerReg,
    dockerImages: pubDockerReg + '/digitalasset',
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
    : { type: 'local', repository: repository(version == '0.3.8' ? 'public' : repositoryValue) };
}

export function repository(repositoryValue?: string): Repository {
  if (repositoryValue === undefined) {
    return repositories.public;
  } else if (repositoryValue === 'private') {
    return repositories.private;
  } else if (repositoryValue === 'public') {
    return repositories.public;
  } else {
    throw new Error(`Unknown artifacts repository: ${repositoryValue}`);
  }
}

export const CHARTS_VERSION: string | undefined = spliceEnvConfig.optionalEnv('CHARTS_VERSION');
