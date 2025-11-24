// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as fs from 'fs';
import * as nodePath from 'path';
import { PathLike } from 'fs';
import { load } from 'js-yaml';

import { config, isDevNet, isMainNet } from './config';
import { spliceConfig } from './config/config';
import { spliceEnvConfig } from './config/envConfig';
import { ClusterBasename, GcpProject, GcpRegion, GcpZone } from './config/gcpConfig';
import { jmxOptions } from './jmx';

/// Environment variables
export const HELM_CHART_TIMEOUT_SEC = Number(config.optionalEnv('HELM_CHART_TIMEOUT_SEC')) || 600;
export const HELM_MAX_HISTORY_SIZE = Number(config.optionalEnv('HELM_MAX_HISTORY_SIZE')) || 0; // 0 => no limit

const MOCK_SPLICE_ROOT = config.optionalEnv('MOCK_SPLICE_ROOT');
export const SPLICE_ROOT = config.requireEnv('SPLICE_ROOT', 'root directory of the repo');
export const PULUMI_STACKS_DIR = config.requireEnv('PULUMI_STACKS_DIR');
// backwards compatibility
export const CLUSTER_BASENAME = ClusterBasename;
export const GCP_PROJECT = GcpProject;
export const GCP_REGION = GcpRegion;
export const GCP_ZONE = GcpZone;
export const CLUSTER_HOSTNAME = config.requireEnv('GCP_CLUSTER_HOSTNAME');
export const PUBLIC_CONFIGS_PATH = config.optionalEnv('PUBLIC_CONFIGS_PATH');
export const PRIVATE_CONFIGS_PATH = config.optionalEnv('PRIVATE_CONFIGS_PATH');

export const HELM_REPO = spliceEnvConfig.requireEnv('OCI_DEV_HELM_REGISTRY');
export const DOCKER_REPO = spliceEnvConfig.requireEnv('CACHE_DEV_DOCKER_REGISTRY');

export const ObservabilityReleaseName = 'prometheus-grafana-monitoring';

export function getDnsNames(): { daDnsName: string; cantonDnsName: string } {
  const daUrlScheme = 'global.canton.network.digitalasset.com';
  const cantonUrlScheme = 'network.canton.global';

  if (CLUSTER_HOSTNAME.includes(daUrlScheme)) {
    return {
      daDnsName: CLUSTER_HOSTNAME,
      cantonDnsName: CLUSTER_HOSTNAME.replace(daUrlScheme, cantonUrlScheme),
    };
  } else if (CLUSTER_HOSTNAME.includes(cantonUrlScheme)) {
    return {
      daDnsName: CLUSTER_HOSTNAME.replace(cantonUrlScheme, daUrlScheme),
      cantonDnsName: CLUSTER_HOSTNAME,
    };
  } else {
    throw new Error(
      'Expected hostname to conform to either DA URL scheme or Canton URL scheme, but got: ' +
        CLUSTER_HOSTNAME
    );
  }
}

export const CLUSTER_NAME = `cn-${CLUSTER_BASENAME}net`;

export const ENABLE_COMETBFT_PRUNING = config.envFlag('ENABLE_COMETBFT_PRUNING', false);

export const COMETBFT_RETAIN_BLOCKS = ENABLE_COMETBFT_PRUNING
  ? parseInt(config.requireEnv('COMETBFT_RETAIN_BLOCKS'))
  : 0;

const enableSequencerPruning = config.envFlag('ENABLE_SEQUENCER_PRUNING', false);
export const sequencerPruningConfig = enableSequencerPruning
  ? {
      enabled: true,
      pruningInterval: config.requireEnv('SEQUENCER_PRUNING_INTERVAL', ''),
      retentionPeriod: config.requireEnv('SEQUENCER_RETENTION_PERIOD', ''),
    }
  : { enabled: false };

export const sequencerTokenExpirationTime: string | undefined = config.optionalEnv(
  'SEQUENCER_TOKEN_EXPIRATION_TIME'
);

export const domainLivenessProbeInitialDelaySeconds: string | undefined = config.optionalEnv(
  'DOMAIN_LIVENESS_PROBE_INITIAL_DELAY_SECONDS'
);

export const svOnboardingPollingInterval = config.optionalEnv('SV_ONBOARDING_POLLING_INTERVAL');

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

export function exactNamespace(
  name: string,
  withIstioInjection = false,
  retainOnDelete?: boolean
): ExactNamespace {
  // Namespace with a fully specified name, exactly as it will
  // appear within Kubernetes. (No Pulumi suffix.)
  const ns = new k8s.core.v1.Namespace(
    name,
    {
      metadata: {
        name,
        labels: withIstioInjection ? { 'istio-injection': 'enabled' } : {},
      },
    },
    {
      retainOnDelete,
    }
  );

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

const _fixedTokens = config.envFlag('CNCLUSTER_FIXED_TOKENS', false);

export function fixedTokens(): boolean {
  return _fixedTokens;
}

export const clusterNetwork = isDevNet ? 'dev' : isMainNet ? 'main' : 'test';

function getClusterDirectory(): string {
  const net = {
    dev: 'DevNet',
    main: 'MainNet',
    test: 'TestNet',
  }[clusterNetwork];

  if (!net) {
    throw new Error(`Unknown cluster network: ${clusterNetwork}`);
  }

  return net;
}

export const clusterDirectory = getClusterDirectory();

export function getPathToPrivateConfigFile(fileName: string): string | undefined {
  const path = PRIVATE_CONFIGS_PATH;

  if (spliceConfig.pulumiProjectConfig.isExternalCluster && !path) {
    throw new Error('isExternalCluster is true but PRIVATE_CONFIGS_PATH is not set');
  }

  if (!path) {
    return undefined;
  }

  return `${path}/configs/${clusterDirectory}/${fileName}`;
}

export function getPathToPublicConfigFile(fileName: string): string | undefined {
  const path = PUBLIC_CONFIGS_PATH;

  if (spliceConfig.pulumiProjectConfig.isExternalCluster && !path) {
    throw new Error('isExternalCluster is true but PUBLIC_CONFIGS_PATH is not set');
  }

  if (!path) {
    return undefined;
  }

  return `${path}/configs/${clusterDirectory}/${fileName}`;
}

// only for use with command.local.Command pulumi objects
export function commandScriptPath(relativeToSplice: string): string {
  const relativeRoot = nodePath.relative(process.cwd(), MOCK_SPLICE_ROOT || SPLICE_ROOT);
  return nodePath.join(relativeRoot, relativeToSplice);
}

export function externalIpRangesFile(): string | undefined {
  if (!spliceConfig.pulumiProjectConfig.isExternalCluster) {
    return undefined;
  }

  return getPathToPrivateConfigFile('allowed-ip-ranges.json');
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

export function conditionalString(condition: boolean, value: string): string {
  return condition ? value : '';
}

export const daContactPoint = 'sv-support@digitalasset.com';

export const getAdditionalJvmOptions = (extraOptions: string | undefined): string =>
  `${jmxOptions()}${extraOptions ? ` ${extraOptions}` : ''}`;
