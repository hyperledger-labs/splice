import { defaultVersion, imageTagOverride } from 'cn-pulumi-common';

export const Version = imageTagOverride || versionFromDefault();

function versionFromDefault() {
  if (defaultVersion.type == 'remote') {
    return defaultVersion.version;
  } else {
    throw new Error('No valid version found; "local" versions not supported');
  }
}
