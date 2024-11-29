import * as k8s from '@pulumi/kubernetes';
import * as inputs from '@pulumi/kubernetes/types/input';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
import * as semver from 'semver';
import { Release } from '@pulumi/kubernetes/helm/v3';
import path from 'path';

import { CnChartVersion, repositories } from './artifacts';
import { config, imagePullPolicy } from './config';
import { spliceConfig } from './config/config';
import { activeVersion } from './domainMigration';
import { SplicePlaceholderResource } from './pulumiUtilResources';
import {
  ChartValues,
  CLUSTER_HOSTNAME,
  CLUSTER_NAME,
  ExactNamespace,
  fixedTokens,
  HELM_CHART_TIMEOUT_SEC,
  HELM_MAX_HISTORY_SIZE,
  loadJsonFromFile,
  loadYamlFromFile,
  REPO_ROOT,
} from './utils';

export type InstalledHelmChart = Release | SplicePlaceholderResource;

// The default type of dependsOn is an unworkable abomination.
export type SpliceCustomResourceOptions = Omit<pulumi.CustomResourceOptions, 'dependsOn'> & {
  dependsOn?: pulumi.Input<pulumi.Resource>[];
};

export function withAddedDependencies(
  opts?: SpliceCustomResourceOptions,
  extraDependsOn?: pulumi.Input<pulumi.Resource>[]
): SpliceCustomResourceOptions {
  return opts
    ? {
        ...opts,
        dependsOn: opts.dependsOn?.concat(extraDependsOn || []),
      }
    : { dependsOn: extraDependsOn };
}

// pulumi.Input<T> allows Promise<T>, which can cause issues with our deployment scripts (i.e. auth0 token cache)
// if not awaited. this custom type is a subset that excludes promises, which gives us some type safety
export type CnInput<T> = T | pulumi.OutputInstance<T>;

const versionsFile: string | undefined = config.optionalEnv('IMAGE_VERSIONS_FILE');
const versionsFromFile: undefined | { [key: string]: { [key: string]: string } } =
  versionsFile && loadJsonFromFile(versionsFile);

function getVersionOverrideFromVersionsFile(
  nsLogicalName: string,
  chartName: string
): string | undefined {
  return (
    versionsFromFile &&
    versionsFromFile[nsLogicalName] &&
    versionsFromFile[nsLogicalName][chartName]
  );
}

function installSpliceHelmChartByNamespaceName(
  nsLogicalName: string,
  nsMetadataName: pulumi.Output<string>,
  name: string,
  chartName: string,
  values: ChartValues = {},
  version: CnChartVersion = activeVersion,
  opts?: SpliceCustomResourceOptions,
  includeNamespaceInName = true,
  affinityAndTolerations = appsAffinityAndTolerations,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): InstalledHelmChart {
  if (spliceConfig.pulumiProjectConfig.installDataOnly) {
    return new SplicePlaceholderResource(name);
  } else {
    return new k8s.helm.v3.Release(
      includeNamespaceInName ? `${nsLogicalName}-${name}` : name,
      {
        name,
        namespace: nsMetadataName,
        chart: chartPath(chartName, version),
        version: versionStringWithPossibleOverride(version, nsLogicalName, chartName),
        repositoryOpts: repositoryOpts(version),
        values: {
          ...cnChartValues(version, chartName, values),
          ...affinityAndTolerations,
          ...imagePullPolicy,
        },
        timeout,
        maxHistory: HELM_MAX_HISTORY_SIZE,
      },
      opts
    );
  }
}

export function installSpliceHelmChart(
  xns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues = {},
  version: CnChartVersion = activeVersion,
  opts?: SpliceCustomResourceOptions,
  includeNamespaceInName = true,
  affinityAndTolerations = appsAffinityAndTolerations,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): InstalledHelmChart {
  return installSpliceHelmChartByNamespaceName(
    xns.logicalName,
    xns.ns.metadata.name,
    name,
    chartName,
    values,
    version,
    opts,
    includeNamespaceInName,
    affinityAndTolerations,
    timeout
  );
}

function cnChartValues(
  version: CnChartVersion,
  chartName: string,
  overrideValues: ChartValues = {}
): ChartValues {
  // This is useful for the `expected` jsons but functionally redundant, so we only do this when using local charts
  const chartDefaultValues =
    version.type === 'local' ? loadYamlFromFile(`${chartPath(chartName, version)}values.yaml`) : {};

  return _.mergeWith(
    {},
    chartDefaultValues,
    {
      imageRepo: version.repository.dockerImages,
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        name: CLUSTER_NAME,
        fixedTokens: fixedTokens(),
        dnsName: CLUSTER_HOSTNAME,
      },
      // TODO(#14409): remove this once migration tests stop using 0.1 releases (we removed this variable in 0.2.0)
      clusterUrl: CLUSTER_HOSTNAME,
    },
    overrideValues,
    (a, b) => (_.isArray(b) ? b : undefined)
  );
}

export function installSpliceRunbookHelmChartByNamespaceName(
  nsMetadataName: pulumi.Output<string> | string,
  nsLogicalName: string,
  name: string,
  chartName: string,
  values: ChartValues,
  version: CnChartVersion = activeVersion,
  opts?: SpliceCustomResourceOptions,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): InstalledHelmChart {
  if (spliceConfig.pulumiProjectConfig.installDataOnly) {
    return new SplicePlaceholderResource(name);
  } else {
    return new k8s.helm.v3.Release(
      name,
      {
        name: name,
        namespace: nsMetadataName,
        chart: chartPath(chartName, version),
        version: versionStringWithPossibleOverride(version, nsLogicalName, chartName),
        repositoryOpts: repositoryOpts(version),
        values: {
          ...values,
          imageRepo: version.repository.dockerImages,
          ...appsAffinityAndTolerations,
          // TODO(#14409): remove this once migration tests stop using 0.1 releases (we removed this variable in 0.2.0)
          clusterUrl: CLUSTER_HOSTNAME,
        },
        timeout,
        maxHistory: HELM_MAX_HISTORY_SIZE,
      },
      opts
    );
  }
}

export function installSpliceRunbookHelmChart(
  ns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues,
  version: CnChartVersion = activeVersion,
  opts?: SpliceCustomResourceOptions,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): InstalledHelmChart {
  return installSpliceRunbookHelmChartByNamespaceName(
    ns.ns.metadata.name,
    ns.logicalName,
    name,
    chartName,
    values,
    version,
    { ...opts, dependsOn: opts?.dependsOn?.concat([ns.ns]) || [] },
    timeout
  );
}

export function chartPath(chartName: string, version: CnChartVersion): string {
  const compatibleName =
    version.type === 'local' || semver.gt(version.version, '0.2.1')
      ? chartName
      : chartName.replace(/^splice/, 'cn');
  return version.type === 'local'
    ? `${path.relative(process.cwd(), REPO_ROOT)}/cluster/helm/${compatibleName}/`
    : version.repository === repositories.private
      ? `${version.repository.helm}/${compatibleName}`
      : compatibleName;
}

function versionStringWithPossibleOverride(
  version: CnChartVersion,
  nsLogicalName: string,
  chartPath: string
) {
  if (version.type === 'local') {
    return undefined;
  } else {
    const versionOverride = getVersionOverrideFromVersionsFile(nsLogicalName, chartPath);
    return versionOverride || version.version;
  }
}

// repository opts are not supported for oci charts
export function repositoryOpts(version: CnChartVersion): inputs.helm.v3.RepositoryOpts | undefined {
  if (version.type === 'local' || version.repository === repositories.private) {
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

export const appsAffinityAndTolerations = {
  affinity: {
    nodeAffinity: {
      requiredDuringSchedulingIgnoredDuringExecution: {
        nodeSelectorTerms: [
          {
            matchExpressions: [
              {
                key: 'cn_apps',
                operator: 'Exists',
              },
            ],
          },
        ],
      },
    },
  },
  tolerations: [
    {
      key: 'cn_apps',
      operator: 'Exists',
      effect: 'NoSchedule',
    },
  ],
};

export const infraAffinityAndTolerations = {
  affinity: {
    nodeAffinity: {
      requiredDuringSchedulingIgnoredDuringExecution: {
        nodeSelectorTerms: [
          {
            matchExpressions: [
              {
                key: 'cn_infra',
                operator: 'Exists',
              },
            ],
          },
        ],
      },
    },
  },
  tolerations: [
    {
      key: 'cn_infra',
      operator: 'Exists',
      effect: 'NoSchedule',
    },
  ],
};
