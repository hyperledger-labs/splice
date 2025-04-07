import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import { config } from 'splice-pulumi-common';

export const gcpProjectId = pulumi.getStack();

export const provider = new gcp.Provider(`provider-${gcpProjectId}`, {
  project: gcpProjectId,
});

export function isMainNet(): boolean {
  // We check also the GCP_CLUSTER_BASENAME for update-expected, because in dump-config we overwrite the project id
  return (
    gcpProjectId === 'da-cn-mainnet' || config.optionalEnv('GCP_CLUSTER_BASENAME') === 'mainzrh'
  );
}
