import * as pulumi from '@pulumi/pulumi';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');
export const clusterDnsName = `${clusterBasename}.network.canton.global`;
export const gcpDnsProject = process.env.GCP_DNS_PROJECT;

if (gcpDnsProject === undefined)
  throw new Error('GCP_DNS_PROJECT environment variable is undefined');
