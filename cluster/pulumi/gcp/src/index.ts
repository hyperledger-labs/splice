import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';

import { GcpProject } from './gcpProject';

export const gcpProjectId = pulumi.getStack();

if (gcpProjectId !== process.env.CLOUDSDK_CORE_PROJECT) {
  throw new Error(
    'The stack name does not match CLOUDSDK_CORE_PROJECT -- check your environment or active stack'
  );
}

const provider = new gcp.Provider(`provider-${gcpProjectId}`, {
  project: gcpProjectId,
});

new GcpProject(gcpProjectId, { gcpProjectId }, { provider });
