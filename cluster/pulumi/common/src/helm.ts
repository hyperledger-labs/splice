import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';

import {
  CHARTS_VERSION,
  ChartValues,
  CLUSTER_BASENAME,
  CLUSTER_DNS_NAME,
  CLUSTER_NAME,
  ExactNamespace,
  fixedTokens,
  HELM_CHART_TIMEOUT_SEC,
  loadYamlFromFile,
  REPO_ROOT,
  requireEnv,
} from './utils';

// The default type of dependsOn is an unworkable abonimation.
export type CNCustomResourceOptions = Omit<pulumi.CustomResourceOptions, 'dependsOn'> & {
  dependsOn?: pulumi.Input<pulumi.Resource>[];
};

// pulumi.Input<T> allows Promise<T>, which can cause issues with our deployment scripts (i.e. auth0 token cache)
// if not awaited. this custom type is a subset that excludes promises, which gives us some type safety
export type CnInput<T> = T | pulumi.OutputInstance<T>;

export type CnChartVersion = { type: 'local' } | { type: 'remote'; version: string };

export const defaultVersion: CnChartVersion =
  CHARTS_VERSION && CHARTS_VERSION !== 'local'
    ? { type: 'remote', version: CHARTS_VERSION }
    : { type: 'local' };

export function installCNHelmChartByNamespaceName(
  prefix: string,
  nsName: pulumi.Output<string>,
  name: string,
  chartName: string,
  values: ChartValues = {},
  version: CnChartVersion = defaultVersion,
  opts?: CNCustomResourceOptions
): Release {
  const release = new k8s.helm.v3.Release(
    `${prefix}-${name}`,
    {
      name,
      namespace: nsName,
      chart: chartPath(chartName, version),
      version: versionString(version),
      repositoryOpts: repositoryOpts(version),
      values: cnChartValues(version, chartName, values),
      timeout: HELM_CHART_TIMEOUT_SEC,
    },
    opts
  );
  return release;
}

export function installCNHelmChart(
  xns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues = {},
  version: CnChartVersion = defaultVersion,
  opts?: CNCustomResourceOptions
): Release {
  return installCNHelmChartByNamespaceName(
    xns.logicalName,
    xns.ns.metadata.name,
    name,
    chartName,
    values,
    version,
    opts
  );
}

function cnChartValues(
  version: CnChartVersion,
  chartPath: string,
  overrideValues: ChartValues = {}
): ChartValues {
  // This is useful for the `expected` jsons but functionally redundant, so we only do this when using local charts
  const chartDefaultValues =
    version.type === 'local'
      ? loadYamlFromFile(process.env.REPO_ROOT + '/cluster/helm/' + chartPath + '/values.yaml')
      : {};

  const imageTagOverride = process.env['IMAGE_TAG'];

  if (imageTagOverride && version.type == 'remote' && version.version != imageTagOverride) {
    // Mixing versions like this sounds like something that is always a bad idea.
    throw new Error(
      `Remote chart version ${version.version} does not match image tag ${imageTagOverride}`
    );
  }

  const values = _.mergeWith(
    {},
    chartDefaultValues,
    {
      // No need to use artifactory as an image repo for our core nodes
      imageRepo: 'us-central1-docker.pkg.dev/da-cn-shared/cn-images',
      cluster: {
        basename: CLUSTER_BASENAME,
        name: CLUSTER_NAME,
        fixedTokens: fixedTokens(),
        dnsName: CLUSTER_DNS_NAME,
      },
      clusterUrl: `${CLUSTER_BASENAME}.network.canton.global`,
    },
    overrideValues,
    imageTagOverride
      ? {
          cluster: {
            imageTag: imageTagOverride,
          },
        }
      : {},
    (a, b) => (_.isArray(b) ? b : undefined)
  );

  return values;
}

export function installCNRunbookHelmChartByNamespaceName(
  ns: pulumi.Output<string> | string,
  name: string,
  chartName: string,
  values: ChartValues,
  version: CnChartVersion = defaultVersion,
  dependsOn: CnInput<pulumi.Resource>[] = []
): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    name,
    {
      name: name,
      namespace: ns,
      chart: chartPath(chartName, version),
      version: versionString(version),
      repositoryOpts: repositoryOpts(version),
      values: {
        ...values,
        // Here we do want to use artifactory images, so we know this works for our partners
        imageRepo:
          version.type === 'local'
            ? 'us-central1-docker.pkg.dev/da-cn-shared/cn-images'
            : undefined,
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
  version: CnChartVersion = defaultVersion,
  dependsOn: CnInput<pulumi.Resource>[] = []
): k8s.helm.v3.Release {
  return installCNRunbookHelmChartByNamespaceName(
    ns.ns.metadata.name,
    name,
    chartName,
    values,
    version,
    dependsOn.concat([ns.ns])
  );
}

function chartPath(chartName: string, version: CnChartVersion): string {
  return version.type === 'local' ? REPO_ROOT + '/cluster/helm/' + chartName + '/' : chartName;
}

function versionString(version: CnChartVersion) {
  return version.type === 'local' ? undefined : version.version;
}

function repositoryOpts(version: CnChartVersion) {
  if (version.type === 'local') {
    return undefined;
  } else {
    const username = requireEnv('ARTIFACTORY_USER', 'Username for jfrog artifactory');
    const password = requireEnv('ARTIFACTORY_PASSWORD', 'Password for jfrog artifactory');
    return {
      repo: 'https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm',
      username: username,
      password: password,
    };
  }
}
