import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  exactNamespace,
  imagePullSecret,
  installSpliceHelmChart,
} from 'splice-pulumi-common';

export function installDocs(): pulumi.Resource {
  const xns = exactNamespace('docs');

  const imagePullDeps = imagePullSecret(xns);

  const dependsOn = imagePullDeps.concat([xns.ns]);

  return installSpliceHelmChart(xns, 'docs', 'cn-docs', {}, activeVersion, { dependsOn });
}
