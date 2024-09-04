import { config, defaultVersion } from 'splice-pulumi-common';

const OPERATOR_IMAGE_VERSION = config.optionalEnv('OPERATOR_IMAGE_VERSION');

export const Version = OPERATOR_IMAGE_VERSION || versionFromDefault();

function versionFromDefault() {
  if (defaultVersion.type == 'remote') {
    return defaultVersion.version;
  } else {
    throw new Error('No valid version found; "local" versions not supported');
  }
}
