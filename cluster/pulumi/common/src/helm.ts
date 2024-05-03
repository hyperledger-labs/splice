import * as k8s from '@pulumi/kubernetes';
import * as inputs from '@pulumi/kubernetes/types/input';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';
import path from 'path';

import { artifactsRepository, CnChartVersion, parsedVersion, repositories } from './artifacts';
import { config } from './config';
import {
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
} from './utils';

// The default type of dependsOn is an unworkable abonimation.
export type CNCustomResourceOptions = Omit<pulumi.CustomResourceOptions, 'dependsOn'> & {
  dependsOn?: pulumi.Input<pulumi.Resource>[];
};

// pulumi.Input<T> allows Promise<T>, which can cause issues with our deployment scripts (i.e. auth0 token cache)
// if not awaited. this custom type is a subset that excludes promises, which gives us some type safety
export type CnInput<T> = T | pulumi.OutputInstance<T>;

const CHARTS_VERSION = config.optionalEnv('CHARTS_VERSION');

export const defaultVersion: CnChartVersion = parsedVersion(CHARTS_VERSION);

const imageTagsFile: string | undefined = config.optionalEnv('IMAGE_VERSIONS_FILE');
const jsonImageVersions: undefined | { [key: string]: { [key: string]: string } } =
  imageTagsFile && loadJsonFromFile(imageTagsFile);
export const imageTagOverride: string | undefined = config.optionalEnv('IMAGE_TAG');

if (defaultVersion.type === 'remote') {
  void pulumi.log.info(
    `Using default version ${JSON.stringify(defaultVersion)}, with image overrides ${JSON.stringify(
      jsonImageVersions
    )} and image tag ${imageTagOverride} for CHARTS_VERSION ${CHARTS_VERSION}`
  );
}

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
  opts?: CNCustomResourceOptions,
  includeNamespaceInName = true,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): Release {
  return new k8s.helm.v3.Release(
    includeNamespaceInName ? `${nsLogicalName}-${name}` : name,
    {
      name,
      namespace: nsMetadataName,
      chart: chartPath(chartName, version),
      version: versionString(version, nsLogicalName, chartName),
      repositoryOpts: repositoryOpts(version),
      values: cnChartValues(nsLogicalName, version, chartName, values),
      timeout,
    },
    opts
  );
}

export function installCNHelmChart(
  xns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues = {},
  version: CnChartVersion = defaultVersion,
  opts?: CNCustomResourceOptions,
  includeNamespaceInName = true,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): Release {
  return installCNHelmChartByNamespaceName(
    xns.logicalName,
    xns.ns.metadata.name,
    name,
    chartName,
    values,
    version,
    opts,
    includeNamespaceInName,
    timeout
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
      imageRepo: repositories.google.dockerImages,
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
  dependsOn: CnInput<pulumi.Resource>[] = [],
  timeout: number = HELM_CHART_TIMEOUT_SEC
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
          version.type === 'local' || artifactsRepository === 'google'
            ? repositories.google.dockerImages
            : undefined,
      },
      timeout,
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
  dependsOn: CnInput<pulumi.Resource>[] = [],
  timeout: number = HELM_CHART_TIMEOUT_SEC
): k8s.helm.v3.Release {
  return installCNRunbookHelmChartByNamespaceName(
    ns.ns.metadata.name,
    ns.logicalName,
    name,
    chartName,
    values,
    version,
    dependsOn.concat([ns.ns]),
    timeout
  );
}

export function chartPath(chartName: string, version: CnChartVersion): string {
  return version.type === 'local'
    ? `${path.relative(process.cwd(), REPO_ROOT)}/cluster/helm/${chartName}/`
    : version.repository === repositories.google
      ? `${version.repository.helm}/${chartName}`
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

// repository opts are not supported for oci charts
export function repositoryOpts(version: CnChartVersion): inputs.helm.v3.RepositoryOpts | undefined {
  if (version.type === 'local' || version.repository === repositories.google) {
    return undefined;
  } else {
    const username = config.requireEnv('ARTIFACTORY_USER', 'Username for jfrog artifactory');
    const password = config.requireEnv('ARTIFACTORY_PASSWORD', 'Password for jfrog artifactory');
    return {
      repo: version.repository.helm,
      username: username,
      password: password,
    };
  }
}
