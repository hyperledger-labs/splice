import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import * as _ from 'lodash';
import { PathLike } from 'fs';
import { load } from 'js-yaml';

import { InfrastructureOutputs } from './infra';

export const config = new pulumi.Config();

export const GLOBAL_TIMEOUT_SEC = 300;

export const CLUSTER_BASENAME = config.require('CLUSTER_BASENAME');
export const CLUSTER_NAME = `cn-${CLUSTER_BASENAME}net`;
export const CLUSTER_DNS_NAME = `${CLUSTER_BASENAME}.network.canton.global`;

// Refrence to upstream infrastructure stack.
export const infraStack = new pulumi.StackReference(`infra.${CLUSTER_BASENAME}`);

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

export function exactNamespace(name: string): ExactNamespace {
  // Namespace with a fully specified name, exactly as it will
  // appear within Kubernetes. (No Pulumi suffix.)
  const ns = new k8s.core.v1.Namespace(name, {
    metadata: {
      name,
    },
  });

  return { ns, logicalName: name };
}

/// Chart Values

// There are a few instances where this pulls data from the outside
// world. To avoid fully declaring these external data types, these are
// modeled as 'any', with the any warning disabled.

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function loadYamlFromFile(path: PathLike): any {
  return load(fs.readFileSync(path, 'utf-8'));
}

function stripJsonComments(rawText: string): string {
  const JSON_COMMENT_REGEX = /\\"|"(?:\\"|[^"])*"|(\/\/.*|\/\*[\s\S]*?\*\/|#.*)/g;

  return rawText.replace(JSON_COMMENT_REGEX, (m, g) => (g ? '' : m));
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function loadJsonFromFile(path: PathLike): any {
  try {
    const content = stripJsonComments(fs.readFileSync(path, 'utf8'));

    return JSON.parse(content);
  } catch (e) {
    pulumi.log.error(`could not read JSON from: ${path}`);
    throw e;
  }
}

export function fixedTokens(): boolean {
  return config.require('FIXED_TOKENS') !== '0';
}

export function cnChartValues(chartPath: string, overrideValues: ChartValues = {}): ChartValues {
  const networkSettings = loadJsonFromFile(
    process.env.REPO_ROOT + '/cluster/network-settings.json'
  );

  const chartDefaultValues = loadYamlFromFile(
    process.env.REPO_ROOT + '/cluster/helm/' + chartPath + '/values.yaml'
  );

  const imageTagOverride = config.get('IMAGE_TAG');

  const values = _.mergeWith(
    {},
    chartDefaultValues,
    {
      imageRepo: 'us-central1-docker.pkg.dev/da-cn-images/cn-images',
      cluster: {
        basename: CLUSTER_BASENAME,
        name: CLUSTER_NAME,
        fixedTokens: fixedTokens(),
        ipAddress: infraStack.requireOutput(InfrastructureOutputs.INGRESS_IP),
        dnsName: CLUSTER_DNS_NAME,
        networkSettings,
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

export function installCNHelmChartByNamespaceName(
  prefix: string,
  nsName: pulumi.Output<string>,
  name: string,
  chartName: string,
  values: ChartValues = {},
  dependsOn: pulumi.Resource[] = []
): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    `helm-${prefix}-${name}`,
    {
      name,
      namespace: nsName,
      chart: process.env.REPO_ROOT + '/cluster/helm/' + chartName + '/',
      values: cnChartValues(chartName, values),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn,
    }
  );
}

export function installCNHelmChart(
  xns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues = {},
  dependsOn: pulumi.Resource[] = []
): k8s.helm.v3.Release {
  return installCNHelmChartByNamespaceName(
    xns.logicalName,
    xns.ns.metadata.name,
    name,
    chartName,
    values,
    dependsOn.concat([xns.ns])
  );
}

// Typically used for overriding chart values.
// The pulumi documentation also doesn't suggest a better type than this. ¯\_(ツ)_/¯
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type ChartValues = { [key: string]: any };

/// Environment variables

export function requireEnv(name: string): string {
  const value = process.env[name];

  if (!value) {
    console.error(`Environment variable ${name} is undefined.`);
    process.exit(1);
  } else {
    return value;
  }
}
