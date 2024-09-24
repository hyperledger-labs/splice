import * as pulumi from '@pulumi/pulumi';
import {
  defaultVersion,
  exactNamespace,
  imagePullSecret,
  installSpliceHelmChart,
} from 'splice-pulumi-common';

export function installDocs(): pulumi.Resource {
  const xns = exactNamespace('docs');

  const imagePullDeps = defaultVersion.type === 'local' ? [] : imagePullSecret(xns);

  const dependsOn = imagePullDeps.concat([xns.ns]);

  return installSpliceHelmChart(xns, 'docs', 'cn-docs', {}, defaultVersion, { dependsOn });
}
