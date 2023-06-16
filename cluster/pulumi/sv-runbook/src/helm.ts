import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { ChartValues, ExactNamespace, requiredEnv } from './utils';

export function installCNSVHelmChartByNamespaceName(
  ns: pulumi.Output<string> | string,
  name: string,
  chartName: string,
  values: ChartValues,
  local: boolean,
  version = '',
  dependsOn: pulumi.Resource[] = []
): k8s.helm.v3.Release {
  const repo_root = requiredEnv('REPO_ROOT', 'root directory of the repo');
  const username = local ? '' : requiredEnv('ARTIFACTORY_USER', 'Username for jfrog artifactory');
  const password = local
    ? ''
    : requiredEnv('ARTIFACTORY_PASSWORD', 'Password for jfrog artifactory');
  return new k8s.helm.v3.Release(
    name,
    {
      name: name,
      chart: local ? repo_root + '/cluster/helm/' + chartName + '/' : chartName,
      namespace: ns,
      version: local ? undefined : version,
      repositoryOpts: local
        ? undefined
        : {
            repo: 'https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm',
            username: username,
            password: password,
          },
      values: {
        ...values,
        imageRepo: local ? 'us-central1-docker.pkg.dev/da-cn-images/cn-images' : undefined,
      },
    },
    {
      dependsOn: dependsOn,
    }
  );
}

export function installCNSVHelmChart(
  ns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues,
  local: boolean,
  version = '',
  dependsOn: pulumi.Resource[] = []
): k8s.helm.v3.Release {
  return installCNSVHelmChartByNamespaceName(
    ns.ns.metadata.name,
    name,
    chartName,
    {
      ...values,
      xNodes: true,
    },
    local,
    version,
    dependsOn.concat([ns.ns])
  );
}
