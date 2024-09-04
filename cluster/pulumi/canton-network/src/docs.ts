import * as pulumi from '@pulumi/pulumi';
import { defaultVersion, exactNamespace, installSpliceHelmChart } from 'splice-pulumi-common';

export function installDocs(): pulumi.Resource {
  const xns = exactNamespace('docs');

  const dependsOn = [xns.ns];

  return installSpliceHelmChart(xns, 'docs', 'cn-docs', {}, defaultVersion, { dependsOn });
}
