import * as pulumi from '@pulumi/pulumi';
import {
  approveDaSupportSvNode,
  config,
  isDevNet,
  isMainNet,
  loadJsonFromFile,
  REPO_ROOT,
} from 'splice-pulumi-common';
import { spliceConfig } from 'splice-pulumi-common/src/config/config';
import { z } from 'zod';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');
export const gcpDnsProject = config.requireEnv('GCP_DNS_PROJECT');

export const InfraConfigSchema = z.object({
  infra: z.object({
    ipWhitelisting: z.object({
      extraWhitelistedIngress: z.array(z.string()).default([]),
    }),
  }),
});

export type Config = z.infer<typeof InfraConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
export const infraConfig = InfraConfigSchema.parse(spliceConfig.clusterConfig).infra;

const daSupportNodeIpRanges: string[] = approveDaSupportSvNode ? ['35.244.74.143/32'] : [];

type IpRangesDict = { [key: string]: IpRangesDict } | string[];

function extractIpRanges(x: IpRangesDict): string[] {
  return Array.isArray(x)
    ? x
    : Object.keys(x).reduce((acc: string[], k: string) => acc.concat(extractIpRanges(x[k])), []);
}

export function loadIPRanges(): string[] {
  const externalIPRangesJson = loadJsonFromFile(
    REPO_ROOT + '/cluster/cn-svc-configs/configs/allowed-ip-ranges-external.json'
  );
  const internalIPRangesJson = loadJsonFromFile(
    REPO_ROOT + '/cluster/allowed-ip-ranges-cn-internal.json'
  );

  const extraWhitelistedIps =
    infraConfig.ipWhitelisting.extraWhitelistedIngress.concat(daSupportNodeIpRanges);

  if (isDevNet) {
    return extractIpRanges(externalIPRangesJson.devnet)
      .concat(extractIpRanges(internalIPRangesJson.devnet))
      .concat(extraWhitelistedIps);
  } else if (isMainNet) {
    return extractIpRanges(externalIPRangesJson.mainnet).concat(
      extractIpRanges(internalIPRangesJson.mainnet)
    );
  } else {
    return extractIpRanges(externalIPRangesJson.testnet)
      .concat(extractIpRanges(internalIPRangesJson.testnet))
      .concat(extraWhitelistedIps);
  }
}
