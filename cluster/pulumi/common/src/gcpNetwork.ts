import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';

const project = gcp.organizations.getProjectOutput({});

// use existing default network (needs to have a private vpc connection)
export const privateNetwork = gcp.compute.Network.get(
  'default',
  pulumi.interpolate`projects/${project.name}/global/networks/default`
);
