import * as pulumi from '@pulumi/pulumi';
import { config } from 'cn-pulumi-common';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');
export const clusterDnsName = `${clusterBasename}.network.canton.global`;
export const gcpDnsProject = config.requireEnv('GCP_DNS_PROJECT');
