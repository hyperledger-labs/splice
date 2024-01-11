import * as pulumi from '@pulumi/pulumi';
import { installCNHelmChartByNamespaceName } from 'cn-pulumi-common';

export function installClusterIngress(
  ingressNsName: pulumi.Output<string>,
  validator: pulumi.Resource,
  splitwell: pulumi.Resource,
  docs: pulumi.Resource
): void {
  const dependsOn = [validator, splitwell, docs];

  installCNHelmChartByNamespaceName(
    'cluster-ingress',
    ingressNsName,
    'cluster-ingress',
    'cn-cluster-ingress-full',
    {},
    [],
    { dependsOn }
  );
}
