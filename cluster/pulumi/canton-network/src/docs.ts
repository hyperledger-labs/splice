import * as pulumi from '@pulumi/pulumi';
import { exactNamespace, installCNHelmChart } from 'cn-pulumi-common';

export function installDocs(): pulumi.Resource {
  const xns = exactNamespace('docs');

  const dependsOn = [xns.ns];

  return installCNHelmChart(xns, 'docs', 'cn-docs', {}, dependsOn);
}
