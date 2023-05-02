import * as k8s from '@pulumi/kubernetes';

import { exactNamespace, installCNHelmChart } from './utils';

export function installDocs(): k8s.helm.v3.Release {
  const xns = exactNamespace('docs');

  const dependsOn = [xns.ns];

  return installCNHelmChart(xns, 'docs', 'cn-docs', {}, dependsOn);
}
