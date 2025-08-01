// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager';
import util from 'node:util';
import { config, loadJsonFromFile, externalIpRangesFile } from 'splice-pulumi-common';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');

export const clusterHostname = config.requireEnv('GCP_CLUSTER_HOSTNAME');
export const clusterBaseDomain = clusterHostname.split('.')[0];

export const gcpDnsProject = config.requireEnv('GCP_DNS_PROJECT');

const MonitoringConfigSchema = z.object({
  alerting: z.object({
    enableNoDataAlerts: z.boolean(),
    alerts: z.object({
      delegatelessContention: z.object({
        thresholdPerNamespace: z.number(),
      }),
      trafficWaste: z.object({
        // Traffic wasted in a single time interval
        burst: z.object({
          kilobytes: z.number(),
          timeRange: z.string(),
        }),
        // Median traffic wasted over multiple time intervals
        sustained: z.object({
          kilobytes: z.number(),
          sampleTimeRange: z.string(),
          timeRange: z.string(),
          quantile: z.number(),
        }),
      }),
      cloudSql: z.object({
        maintenance: z.boolean(),
      }),
      cometbft: z.object({
        expectedMaxBlocksPerSecond: z.number(),
      }),
      loadTester: z.object({
        minRate: z.number(),
      }),
    }),
    logAlerts: z.object({}).catchall(z.string()).default({}),
  }),
});
export const InfraConfigSchema = z.object({
  infra: z.object({
    ipWhitelisting: z
      .object({
        extraWhitelistedIngress: z.array(z.string()).default([]),
      })
      .optional(),
    prometheus: z.object({
      storageSize: z.string(),
      retentionDuration: z.string(),
      retentionSize: z.string(),
    }),
    istio: z.object({
      enableIngressAccessLogging: z.boolean(),
    }),
    extraCustomResources: z.object({}).catchall(z.any()).default({}),
  }),
  monitoring: MonitoringConfigSchema,
});

export type Config = z.infer<typeof InfraConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = InfraConfigSchema.parse(clusterYamlConfig);

console.error(
  `Loaded infra config: ${util.inspect(fullConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

export const infraConfig = fullConfig.infra;
export const monitoringConfig = fullConfig.monitoring;

type IpRangesDict = { [key: string]: IpRangesDict } | string[];

function extractIpRanges(x: IpRangesDict): string[] {
  return Array.isArray(x)
    ? x
    : Object.keys(x).reduce((acc: string[], k: string) => acc.concat(extractIpRanges(x[k])), []);
}

export function loadIPRanges(): pulumi.Output<string[]> {
  const file = externalIpRangesFile();
  const externalIpRanges = file ? extractIpRanges(loadJsonFromFile(file)) : [];

  const internalWhitelistedIps = getSecretVersionOutput({
    secret: 'pulumi-internal-whitelists',
  }).apply(whitelists => {
    const secretData = whitelists.secretData;
    const json = JSON.parse(secretData);
    const ret: string[] = [];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    json.forEach((ip: any) => {
      ret.push(ip);
    });
    return ret;
  });

  const configWhitelistedIps = infraConfig.ipWhitelisting?.extraWhitelistedIngress || [];

  return internalWhitelistedIps.apply(whitelists =>
    whitelists.concat(externalIpRanges).concat(configWhitelistedIps)
  );
}
