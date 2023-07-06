import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_BASENAME } from 'cn-pulumi-common';

export function configureForwardAll(ingressNs: pulumi.Output<string>): k8s.helm.v3.Release {
  const repo_root = process.env.REPO_ROOT;
  return new k8s.helm.v3.Release('fwd-all', {
    name: 'fwd-all',
    namespace: ingressNs,
    chart: repo_root + '/cluster/helm/cn-istio-fwd/',
    values: {
      cluster: {
        basename: CLUSTER_BASENAME,
      },
    },
  });
}
