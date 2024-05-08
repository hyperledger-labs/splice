import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import { PathLike } from 'fs';
import { load } from 'js-yaml';

import { config } from './config';

/// Environment variables
export const HELM_CHART_TIMEOUT_SEC = Number(config.optionalEnv('HELM_CHART_TIMEOUT_SEC')) || 480;

export const SV_APP_HELM_CHART_TIMEOUT_SEC = 1000;

export const REPO_ROOT = config.requireEnv('REPO_ROOT', 'root directory of the repo');
export const CLUSTER_BASENAME = config.requireEnv('GCP_CLUSTER_BASENAME');
export const CLUSTER_HOSTNAME = config.requireEnv('GCP_CLUSTER_HOSTNAME');
export const GCP_PROJECT = config.requireEnv('CLOUDSDK_CORE_PROJECT');
export const CLUSTER_NAME = `cn-${CLUSTER_BASENAME}net`;

export const EXPECTED_MAX_BLOCK_RATE_PER_SECOND =
  config.optionalEnv('EXPECTED_MAX_BLOCK_RATE_PER_SECOND') || '2.5';

export const approveDaSupportSvNode = config.envFlag('APPROVE_DA_SUPPORT_SV_NODE', false);

const daSupportNodeIpRanges: string[] = approveDaSupportSvNode ? ['35.244.74.143/32'] : [];

export const daSupportApprovedIdentities: ApprovedSvIdentity[] = approveDaSupportSvNode
  ? [
      {
        name: 'Digital-Asset-Support-SV',
        publicKey:
          'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAED20NyAmxCu7clk65HyRQlqXO0GrPeDqVhNWPNyPvOjLwnwwyVXiwBB5yVJTldOi6AJKZM8bowIcLSwoccIiPKA==',
        rewardWeightBps: 10000, // dummy weight
      },
    ]
  : [];

export type ApprovedSvIdentity = {
  name: string;
  publicKey: string | pulumi.Output<string>;
  rewardWeightBps: number;
};

export const isMainNet = config.envFlag('IS_MAINNET', false);
export const isDevNet = config.envFlag('IS_DEVNET', true) && !isMainNet;
export const clusterSmallDisk = config.envFlag('CLUSTER_SMALL_DISK', false);
export const publicPrometheusRemoteWrite = config.envFlag('PUBLIC_PROMETHEUS_REMOTE_WRITE', false);

const enableSequencerPruning = config.envFlag('ENABLE_SEQUENCER_PRUNING', false);
export const sequencerPruningConfig = enableSequencerPruning
  ? {
      enabled: true,
      pruningInterval: config.requireEnv('SEQUENCER_PRUNING_INTERVAL', ''),
      retentionPeriod: config.requireEnv('SEQUENCER_RETENTION_PERIOD', ''),
    }
  : { enabled: false };

const lowResourceSequencer = config.envFlag('SEQUENCER_LOW_RESOURCES', false);
export const sequencerResources: { resources?: k8s.types.input.core.v1.ResourceRequirements } =
  lowResourceSequencer
    ? {
        resources: {
          limits: {
            cpu: '3',
            memory: '4Gi',
          },
          requests: {
            cpu: '1',
            memory: '2Gi',
          },
        },
      }
    : {};
export const sequencerTokenExpirationTime: string | undefined = config.optionalEnv(
  'SEQUENCER_TOKEN_EXPIRATION_TIME'
);

export const svOnboardingPollingInterval = config.optionalEnv('SV_ONBOARDING_POLLING_INTERVAL');

// Refrence to upstream infrastructure stack.
export const infraStack = new pulumi.StackReference(`organization/infra/infra.${CLUSTER_BASENAME}`);

export const disableCantonAutoInit = config.envFlag('DISABLE_CANTON_AUTO_INIT', false);

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

const _fixedTokens = config.envFlag('CNCLUSTER_FIXED_TOKENS', false);

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
    REPO_ROOT + '/cluster/allowed-ip-ranges-external.json'
  );
  const internalIPRangesJson = loadJsonFromFile(
    REPO_ROOT + '/cluster/allowed-ip-ranges-cn-internal.json'
  );

  if (isDevNet) {
    return extractIpRanges(externalIPRangesJson.devnet)
      .concat(extractIpRanges(internalIPRangesJson.devnet))
      .concat(daSupportNodeIpRanges);
  } else {
    return extractIpRanges(externalIPRangesJson.testnet)
      .concat(extractIpRanges(internalIPRangesJson.testnet))
      .concat(daSupportNodeIpRanges);
  }
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
