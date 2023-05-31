import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

const clusterBasename = process.env.GCP_CLUSTER_BASENAME;
const infraStack = new pulumi.StackReference(`infra.${clusterBasename}`);
const ingressNs = infraStack.getOutput('ingressNs');

function configureForwardAll(ingressNs: pulumi.Output<string>) {
  const repo_root = process.env.REPO_ROOT;
  return new k8s.helm.v3.Release('fwd-all', {
    name: 'fwd-all',
    namespace: ingressNs,
    chart: repo_root + '/cluster/helm/cn-istio-fwd/',
    values: {
      cluster: {
        basename: clusterBasename,
      },
    },
  });
}

configureForwardAll(ingressNs as pulumi.Output<string>);
