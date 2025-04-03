import { config } from 'splice-pulumi-common';

import { gcpProjectId } from './consts';
import { installInternalWhitelists } from './whitelists';

const GCP_PROJECT = config.requireEnv('CLOUDSDK_CORE_PROJECT');
if (!GCP_PROJECT) {
  throw new Error('CLOUDSDK_CORE_PROJECT is undefined');
}
if (gcpProjectId !== GCP_PROJECT) {
  throw new Error(
    'The stack name does not match CLOUDSDK_CORE_PROJECT -- check your environment or active stack'
  );
}

function main() {
  installInternalWhitelists();
}

main();
