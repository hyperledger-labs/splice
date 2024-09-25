import * as pulumi from '@pulumi/pulumi';
import { config } from 'splice-pulumi-common';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');
export const gcpDnsProject = config.requireEnv('GCP_DNS_PROJECT');
