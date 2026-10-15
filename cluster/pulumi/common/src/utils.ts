// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
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
export const CACHE_GHCR = spliceEnvConfig.requireEnv('CACHE_GHCR');

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

// Name of the Cloud Armor security policy created for this cluster (see
// cluster/pulumi/infra/src/cloudArmor.ts). Shared so that alerts can be scoped to
// exactly the policy of this cluster.
export const CLOUD_ARMOR_POLICY_NAME = `waf-whitelist-throttle-ban-${CLUSTER_BASENAME}`;

// Every group of rules of the Cloud Armor policy
// owns a block of 9 digit priorities sharing a unique leading digit. The request logs
// only carry the priority of the rule that matched, not its name, so this lets alerts
// and dashboards tell the groups apart by priority prefix alone, e.g.
// `rule_priority=~"1[0-9]{8}"` for the WAF rules. The default deny rule and its
// preview-only twin keep the two highest (10 digit) priorities.
export const CLOUD_ARMOR_RULE_GROUP_PREFIXES = {
  // preconfigured (OWASP CRS based) WAF rules
  waf: 1,
  // allow rules for the whitelisted source IPs
  ipWhitelist: 2,
  // allow / per source IP throttle rules of the public endpoints
  publicEndpoints: 3,
} as const;
export type CloudArmorRuleGroup = keyof typeof CLOUD_ARMOR_RULE_GROUP_PREFIXES;
// Number of priorities available to each rule group.
export const CLOUD_ARMOR_RULE_GROUP_SIZE = 100_000_000;

export function cloudArmorRulePriority(group: CloudArmorRuleGroup, offset: number): number {
  if (!Number.isInteger(offset) || offset < 0 || offset >= CLOUD_ARMOR_RULE_GROUP_SIZE) {
    throw new Error(
      `Cloud Armor ${group} rule priority offset ${offset} is outside [0, ${CLOUD_ARMOR_RULE_GROUP_SIZE})`
    );
  }
  return CLOUD_ARMOR_RULE_GROUP_PREFIXES[group] * CLOUD_ARMOR_RULE_GROUP_SIZE + offset;
}

export function cloudArmorRulePriorityRegex(group: CloudArmorRuleGroup): string {
  return `${CLOUD_ARMOR_RULE_GROUP_PREFIXES[group]}[0-9]{8}`;
}

// The GKE gateway is fronted by a regional external application load balancer, whose
// request logs (carrying the Cloud Armor decisions) use `http_external_regional_lb_rule`;
// `http_load_balancer` is accepted as well so filters keep working if the gateway ever
// becomes global.
export const LB_REQUEST_LOG_RESOURCE_TYPES = [
  'http_external_regional_lb_rule',
  'http_load_balancer',
];

export function lbRequestLogResourceTypesFilter(): string {
  return `resource.type=(${LB_REQUEST_LOG_RESOURCE_TYPES.map(t => `"${t}"`).join(' OR ')})`;
}

export const sequencerTokenExpirationTime: string | undefined = config.optionalEnv(
  'SEQUENCER_TOKEN_EXPIRATION_TIME'
);

export const domainLivenessProbeInitialDelaySeconds: string | undefined = config.optionalEnv(
  'DOMAIN_LIVENESS_PROBE_INITIAL_DELAY_SECONDS'
);

export const svOnboardingPollingInterval = config.optionalEnv('SV_ONBOARDING_POLLING_INTERVAL');

export * from './namespace';

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

export function loadJsonFromFile(path: PathLike): any {
  try {
    const content = stripJsonComments(fs.readFileSync(path, 'utf8'));

    return JSON.parse(content);
  } catch (e) {
    void pulumi.log.error(`could not read JSON from: ${path}`);
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
