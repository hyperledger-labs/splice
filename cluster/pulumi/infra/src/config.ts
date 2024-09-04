import * as pulumi from '@pulumi/pulumi';
import { config } from 'splice-pulumi-common';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');
export const clusterDnsName = config.requireEnv('GCP_CLUSTER_HOSTNAME');
export const gcpDnsProject = config.requireEnv('GCP_DNS_PROJECT');
