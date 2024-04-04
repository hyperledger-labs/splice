import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';
import path from 'path';

import {
  CHARTS_VERSION,
  ChartValues,
  CLUSTER_BASENAME,
  CLUSTER_DNS_NAME,
  CLUSTER_NAME,
  ExactNamespace,
  fixedTokens,
  HELM_CHART_TIMEOUT_SEC,
  loadJsonFromFile,
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

const imageTagsFile: string | undefined = process.env['IMAGE_VERSIONS_FILE'];
const jsonImageVersions: undefined | { [key: string]: { [key: string]: string } } =
  imageTagsFile && loadJsonFromFile(imageTagsFile);
const imageTagOverride: string | undefined = process.env['IMAGE_TAG'];

function getVersionOverrideFromVersionsFile(
  nsLogicalName: string,
  chartName: string
): string | undefined {
  return (
    jsonImageVersions &&
    jsonImageVersions[nsLogicalName] &&
    jsonImageVersions[nsLogicalName][chartName]
  );
}

export function installCNHelmChartByNamespaceName(
  nsLogicalName: string,
  nsMetadataName: pulumi.Output<string>,
  name: string,
  chartName: string,
  values: ChartValues = {},
  version: CnChartVersion = defaultVersion,
  opts?: CNCustomResourceOptions
): Release {
  const release = new k8s.helm.v3.Release(
    `${nsLogicalName}-${name}`,
    {
      name,
      namespace: nsMetadataName,
      chart: chartPath(chartName, version),
      version: versionString(version, nsLogicalName, chartName),
      repositoryOpts: repositoryOpts(version),
      values: cnChartValues(nsLogicalName, version, chartName, values),
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
  nsLogicalName: string,
  version: CnChartVersion,
  chartName: string,
  overrideValues: ChartValues = {}
): ChartValues {
  // This is useful for the `expected` jsons but functionally redundant, so we only do this when using local charts
  const chartDefaultValues =
    version.type === 'local' ? loadYamlFromFile(`${chartPath(chartName, version)}values.yaml`) : {};

  const imageVersionFromFile = getVersionOverrideFromVersionsFile(nsLogicalName, chartName);
  const finalOverride = imageVersionFromFile || imageTagOverride;

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
    finalOverride
      ? {
          cluster: {
            imageTag: finalOverride,
          },
        }
      : {},
    (a, b) => (_.isArray(b) ? b : undefined)
  );

  return values;
}

export function installCNRunbookHelmChartByNamespaceName(
  nsMetadataName: pulumi.Output<string> | string,
  nsLogicalName: string,
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
      namespace: nsMetadataName,
      chart: chartPath(chartName, version),
      version: versionString(version, nsLogicalName, chartName),
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
    ns.logicalName,
    name,
    chartName,
    values,
    version,
    dependsOn.concat([ns.ns])
  );
}

function chartPath(chartName: string, version: CnChartVersion): string {
  return version.type === 'local'
    ? `${path.relative(process.cwd(), REPO_ROOT)}/cluster/helm/${chartName}/`
    : chartName;
}

function versionString(version: CnChartVersion, nsLogicalName: string, chartPath: string) {
  if (version.type === 'local') {
    return undefined;
  } else {
    const versionOverride = getVersionOverrideFromVersionsFile(nsLogicalName, chartPath);
    return versionOverride || version.version;
  }
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
