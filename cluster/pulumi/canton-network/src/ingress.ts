import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { installCNHelmChartByNamespaceName } from 'cn-pulumi-common';

export function installClusterIngress(
  ingressNsName: pulumi.Output<string>,
  validator: k8s.helm.v3.Release,
  splitwell: k8s.helm.v3.Release,
  docs: k8s.helm.v3.Release
): void {
  const dependsOn = [validator, splitwell, docs];

  installCNHelmChartByNamespaceName(
    'cluster-ingress',
    ingressNsName,
    'cluster-ingress',
    'cn-cluster-ingress-full',
    {},
    dependsOn
  );
}
