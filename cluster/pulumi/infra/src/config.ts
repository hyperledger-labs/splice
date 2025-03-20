import * as pulumi from '@pulumi/pulumi';
import util from 'node:util';
import {
  approveDaSupportSvNode,
  svPrivateConfigsClusterDirectory,
  config,
  isDevNet,
  isMainNet,
  loadJsonFromFile,
  SPLICE_ROOT,
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
    ipWhitelisting: z.object({
      extraWhitelistedIngress: z.array(z.string()).default([]),
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

const daSupportNodeIpRanges: string[] = approveDaSupportSvNode ? ['35.244.74.143/32'] : [];

type IpRangesDict = { [key: string]: IpRangesDict } | string[];

function extractIpRanges(x: IpRangesDict): string[] {
  return Array.isArray(x)
    ? x
    : Object.keys(x).reduce((acc: string[], k: string) => acc.concat(extractIpRanges(x[k])), []);
}

export function loadIPRanges(): string[] {
  const externalIpRanges = spliceConfig.pulumiProjectConfig.isExternalCluster
    ? extractIpRanges(
        loadJsonFromFile(`${svPrivateConfigsClusterDirectory}/allowed-ip-ranges.json`)
      )
    : [];
  const internalIPRangesJson = loadJsonFromFile(
    SPLICE_ROOT + '/cluster/allowed-ip-ranges-cn-internal.json'
  );

  const extraWhitelistedIps =
    infraConfig.ipWhitelisting.extraWhitelistedIngress.concat(daSupportNodeIpRanges);

  if (isDevNet) {
    return externalIpRanges
      .concat(extractIpRanges(internalIPRangesJson.devnet))
      .concat(extraWhitelistedIps);
  } else if (isMainNet) {
    return externalIpRanges.concat(extractIpRanges(internalIPRangesJson.mainnet));
  } else {
    return externalIpRanges
      .concat(extractIpRanges(internalIPRangesJson.testnet))
      .concat(extraWhitelistedIps);
  }
}
