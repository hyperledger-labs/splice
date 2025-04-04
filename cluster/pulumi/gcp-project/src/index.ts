import { config } from 'splice-pulumi-common';

import { gcpProjectId } from './consts';
import { installAll } from './gcp-project';

const GCP_PROJECT = config.requireEnv('CLOUDSDK_CORE_PROJECT');
if (!GCP_PROJECT) {
  throw new Error('CLOUDSDK_CORE_PROJECT is undefined');
}
if (gcpProjectId !== GCP_PROJECT) {
  throw new Error(
    `The stack name (${gcpProjectId}) does not match CLOUDSDK_CORE_PROJECT (${GCP_PROJECT}) -- check your environment or active stack`
  );
}

function main() {
  return installAll();
}

main();
