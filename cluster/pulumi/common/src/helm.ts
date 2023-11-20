import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { ChartValues, ExactNamespace, HELM_CHART_TIMEOUT_SEC, requireEnv } from './utils';

// pulumi.Input<T> allows Promise<T>, which can cause issues with our deployment scripts (i.e. auth0 token cache)
// if not awaited. this custom type is a subset that excludes promises, which gives us some type safety
export type CnInput<T> = T | pulumi.OutputInstance<T>;

export function installCNRunbookHelmChartByNamespaceName(
  ns: pulumi.Output<string> | string,
  name: string,
  chartName: string,
  values: ChartValues,
  local: boolean,
  version = '',
  dependsOn: CnInput<pulumi.Resource>[] = []
): k8s.helm.v3.Release {
  const repo_root = requireEnv('REPO_ROOT', 'root directory of the repo');
  const username = local ? '' : requireEnv('ARTIFACTORY_USER', 'Username for jfrog artifactory');
  const password = local
    ? ''
    : requireEnv('ARTIFACTORY_PASSWORD', 'Password for jfrog artifactory');
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
      timeout: HELM_CHART_TIMEOUT_SEC,
    },
    {
      dependsOn: dependsOn,
    }
  );
}

export function installCNRunbookHelmChart(
  ns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues,
  local: boolean,
  version = '',
  dependsOn: CnInput<pulumi.Resource>[] = []
): k8s.helm.v3.Release {
  return installCNRunbookHelmChartByNamespaceName(
    ns.ns.metadata.name,
    name,
    chartName,
    values,
    local,
    version,
    dependsOn.concat([ns.ns])
  );
}
