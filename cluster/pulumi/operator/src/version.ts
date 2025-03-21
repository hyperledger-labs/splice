import { config, activeVersion } from 'splice-pulumi-common';

const OPERATOR_IMAGE_VERSION = config.optionalEnv('OPERATOR_IMAGE_VERSION');

export const Version = OPERATOR_IMAGE_VERSION || versionFromDefault();

function versionFromDefault() {
  if (activeVersion.type == 'remote') {
    return activeVersion.version;
  } else {
    throw new Error('No valid version found; "local" versions not supported');
  }
}
