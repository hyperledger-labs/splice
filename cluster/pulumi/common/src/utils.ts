import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { CustomResourceOptions } from '@pulumi/pulumi';
import { PathLike } from 'fs';
import { load } from 'js-yaml';

export const config = new pulumi.Config();

export const HELM_CHART_TIMEOUT_SEC = 480;

export const REPO_ROOT = requireEnv('REPO_ROOT', 'root directory of the repo');
export const CLUSTER_BASENAME = requireEnv('GCP_CLUSTER_BASENAME');
export const CLUSTER_NAME = `cn-${CLUSTER_BASENAME}net`;
export const CLUSTER_DNS_NAME = `${CLUSTER_BASENAME}.network.canton.global`;

/// Environment variables

export function requireEnv(name: string, msg = ''): string {
  const value = process.env[name];

  if (!value) {
    console.error(
      `FATAL: Environment variable ${name} is undefined. Shutting down.` +
        (msg != '' ? `(should define: ${msg})` : '')
    );
    process.exit(1);
  } else {
    return value;
  }
}

export function envFlag(flagName: string, defaultFlag = false): boolean {
  const varVal = process.env[flagName];

  let flag = defaultFlag;

  if (varVal) {
    const val = varVal.toLowerCase();

    if (val === 't' || val === 'true' || val === 'y' || val === 'yes' || val === '1') {
      flag = true;
    } else if (val === 'f' || val === 'false' || val === 'n' || val === 'no' || val === '0') {
      flag = false;
    } else {
      console.error(
        `FATAL: Flag environment variable ${flagName} has unexpected value: ${varVal}.`
      );
      process.exit(1);
    }
  }

  console.error(`Environment Flag ${flagName} = ${flag} (${varVal})`);

  return flag;
}

export const isDevNet = envFlag('IS_DEVNET', true);
export const clusterLargeDisk = envFlag('CLUSTER_LARGE_DISK', false);

const enableSequencerPruning = envFlag('ENABLE_SEQUENCER_PRUNING', false);
export const sequencerPruningConfig = enableSequencerPruning
  ? {
      enabled: true,
      pruningInterval: requireEnv('SEQUENCER_PRUNING_INTERVAL', ''),
      retentionPeriod: requireEnv('SEQUENCER_RETENTION_PERIOD', ''),
    }
  : { enabled: false };

// Refrence to upstream infrastructure stack.
export const infraStack = new pulumi.StackReference(`organization/infra/infra.${CLUSTER_BASENAME}`);

/// Kubernetes Namespace

// There is no way to read the logical name off a Namespace.  Exactly
// specified namespaces are therefore returned as a tuple with the
// logical name, to allow it to be used to ensure distinct Pulumi
// logical names when creating objects of the same name in different
// Kubernetes namespaces.
//
// See: https://github.com/pulumi/pulumi/issues/5234
export interface ExactNamespace {
  ns: k8s.core.v1.Namespace;
  logicalName: string;
}

export function exactNamespace(name: string, withIstioInjection = false): ExactNamespace {
  // Namespace with a fully specified name, exactly as it will
  // appear within Kubernetes. (No Pulumi suffix.)
  const ns = new k8s.core.v1.Namespace(name, {
    metadata: {
      name,
      labels: withIstioInjection ? { 'istio-injection': 'enabled' } : {},
    },
  });

  return { ns, logicalName: name };
}

/// Chart Values

// There are a few instances where this pulls data from the outside
// world. To avoid fully declaring these external data types, these are
// modeled as 'any', with the any warning disabled.

/* eslint-disable @typescript-eslint/no-explicit-any */
export function loadYamlFromFile(
  path: PathLike,
  replaceStrings: { [template: string]: string } = {}
): any {
  let yamlStr = fs.readFileSync(path, 'utf-8');
  for (const t in replaceStrings) {
    yamlStr = yamlStr.replaceAll(t, replaceStrings[t]);
  }
  return load(yamlStr) as ChartValues;
}

function stripJsonComments(rawText: string): string {
  const JSON_COMMENT_REGEX = /\\"|"(?:\\"|[^"])*"|(\/\/.*|\/\*[\s\S]*?\*\/|#.*)/g;

  return rawText.replace(JSON_COMMENT_REGEX, (m, g) => (g ? '' : m));
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function loadJsonFromFile(path: PathLike): any {
  try {
    const content = stripJsonComments(fs.readFileSync(path, 'utf8'));

    return JSON.parse(content);
  } catch (e) {
    console.error(`could not read JSON from: ${path}`);
    throw e;
  }
}

const _fixedTokens = envFlag('CNCLUSTER_FIXED_TOKENS', false);

export function fixedTokens(): boolean {
  return _fixedTokens;
}

type IpRangesDict = { [key: string]: IpRangesDict } | string[];

function extractIpRanges(x: IpRangesDict): string[] {
  return Array.isArray(x)
    ? x
    : Object.keys(x).reduce((acc: string[], k: string) => acc.concat(extractIpRanges(x[k])), []);
}

export function loadIPRanges(): string[] {
  const externalIPRangesJson = loadJsonFromFile(
    process.env.REPO_ROOT + '/cluster/allowed-ip-ranges-external.json'
  );
  const internalIPRangesJson = loadJsonFromFile(
    process.env.REPO_ROOT + '/cluster/allowed-ip-ranges-cn-internal.json'
  );

  if (isDevNet) {
    return extractIpRanges(externalIPRangesJson.devnet).concat(
      extractIpRanges(internalIPRangesJson.devnet)
    );
  } else {
    return extractIpRanges(externalIPRangesJson.testnet).concat(
      extractIpRanges(internalIPRangesJson.testnet)
    );
  }
}

export function cnChartValues(chartPath: string, overrideValues: ChartValues = {}): ChartValues {
  const chartDefaultValues = loadYamlFromFile(
    process.env.REPO_ROOT + '/cluster/helm/' + chartPath + '/values.yaml'
  );

  const imageTagOverride = process.env['IMAGE_TAG'];

  const values = _.mergeWith(
    {},
    chartDefaultValues,
    {
      imageRepo: 'us-central1-docker.pkg.dev/da-cn-images/cn-images',
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

// The way our databases are setup, they usually require some knottying, e.g.,
// the scan helm chart requires the postgres db server to be available
// but the postgres exporter requires the scan init container to create the db.
// So we have the dependency chain "db server -> scan helm chart -> db exporter".
// We want to make it impossible to avoid installing the metrics so we special case
// db dependencies in installCNHelmChart.
export type Database = {
  db: pulumi.Resource;
  installMetrics: (dependsOn: pulumi.Input<pulumi.Resource>[]) => pulumi.Resource;
};

// The default type of dependsOn is an unworkable abonimation.
export type CNCustomResourceOptions = Omit<CustomResourceOptions, 'dependsOn'> & {
  dependsOn?: pulumi.Input<pulumi.Resource>[];
};

export function installCNHelmChartByNamespaceName(
  prefix: string,
  nsName: pulumi.Output<string>,
  name: string,
  chartName: string,
  values: ChartValues = {},
  dbDependsOn?: Database[],
  opts?: CNCustomResourceOptions
): Release {
  const dbs: pulumi.Resource[] = (dbDependsOn || []).map(x => x.db);
  const dependsOn: pulumi.Input<pulumi.Resource>[] = opts?.dependsOn
    ? [...opts.dependsOn, ...dbs]
    : dbs;
  const release = new k8s.helm.v3.Release(
    `${prefix}-${name}`,
    {
      name,
      namespace: nsName,
      chart: process.env.REPO_ROOT + '/cluster/helm/' + chartName + '/',
      values: cnChartValues(chartName, values),
      timeout: HELM_CHART_TIMEOUT_SEC,
    },
    { ...opts, dependsOn }
  );
  (dbDependsOn || []).forEach(({ installMetrics }) => {
    installMetrics([release]);
  });
  return release;
}

export function installCNHelmChart(
  xns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues = {},
  dbDependsOn: Database[],
  opts?: CNCustomResourceOptions
): Release {
  return installCNHelmChartByNamespaceName(
    xns.logicalName,
    xns.ns.metadata.name,
    name,
    chartName,
    values,
    dbDependsOn,
    opts
  );
}

// Typically used for overriding chart values.
// The pulumi documentation also doesn't suggest a better type than this. ¯\_(ツ)_/¯
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type ChartValues = { [key: string]: any };

// base64 encoding

// btoa is only available in DOM so inline the definition here.
export const btoa: (s: string) => string = (s: string) => Buffer.from(s).toString('base64');

export function sanitizedForHelm(value: string): string {
  return value.replaceAll('_', '-');
}

export function sanitizedForPostgres(value: string): string {
  return value.replaceAll('-', '_');
}
