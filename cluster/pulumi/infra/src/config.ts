import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager';
import util from 'node:util';
import {
  config,
  loadJsonFromFile,
  PRIVATE_CONFIGS_PATH,
  clusterDirectory,
} from 'splice-pulumi-common';
import { spliceConfig } from 'splice-pulumi-common/src/config/config';
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
      trafficWaste: z.object({
        kilobytes: z.number(),
        overMinutes: z.number(),
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
  }),
});
export const InfraConfigSchema = z.object({
  infra: z.object({
    prometheus: z.object({
      storageSize: z.string(),
      retentionDuration: z.string(),
      retentionSize: z.string(),
    }),
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
  if (spliceConfig.pulumiProjectConfig.isExternalCluster && !PRIVATE_CONFIGS_PATH) {
    throw new Error('isExternalCluster is true but PRIVATE_CONFIGS_PATH is not set');
  }

  const externalIpRanges = spliceConfig.pulumiProjectConfig.isExternalCluster
    ? extractIpRanges(
        loadJsonFromFile(
          `${PRIVATE_CONFIGS_PATH}/configs/${clusterDirectory}/allowed-ip-ranges.json`
        )
      )
    : [];

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

  return internalWhitelistedIps.apply(whitelists => whitelists.concat(externalIpRanges));
}
