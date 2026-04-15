// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  config,
  loadJsonFromFile,
  externalIpRangesFile,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager';
import util from 'node:util';
import { z } from 'zod';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');

export const clusterHostname = config.requireEnv('GCP_CLUSTER_HOSTNAME');
export const clusterBaseDomain = clusterHostname.split('.')[0];

export const gcpDnsProject = config.requireEnv('GCP_DNS_PROJECT');

const CloudArmorConfigSchema = z.object({
  enabled: z.boolean(),
  // "preview" is not pulumi preview, but https://cloud.google.com/armor/docs/security-policy-overview#preview_mode
  allRulesPreviewOnly: z.boolean(),
  publicEndpoints: z
    .object({})
    .catchall(
      z.object({
        rulePreviewOnly: z.boolean().default(false),
        hostname: z.string().regex(/^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*$/, 'valid DNS hostname'),
        pathPrefix: z.string().regex(/^\/[^"]*$/, 'HTTP request path starting with /'),
        throttleAcrossAllEndpointsAllIps: z.object({
          withinIntervalSeconds: z.number().positive(),
          maxRequestsBeforeHttp429: z
            .number()
            .min(0, '0 to disallow requests or positive to allow'),
        }),
      })
    )
    .default({}),
});
export const InfraConfigSchema = z.object({
  infra: z.object({
    ipWhitelisting: z
      .object({
        extraWhitelistedIngress: z.array(z.string()).default([]),
        excludedIps: z.array(z.string()).default([]),
      })
      .optional(),
    enableGCReaperJob: z.boolean().default(false),
    gkeGateway: z.object({
      proxyForIstioHttp: z.boolean(),
    }),
    istio: z.object({
      enableIngressAccessLogging: z.boolean(),
      enableClusterAccessLogging: z.boolean().default(false),
      istiodValues: z.object({}).catchall(z.any()).default({}),
    }),
    extraCustomResources: z.object({}).catchall(z.any()).default({}),
  }),
  cloudArmor: CloudArmorConfigSchema,
});

export type CloudArmorConfig = z.infer<typeof CloudArmorConfigSchema>;

export type Config = z.infer<typeof InfraConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = InfraConfigSchema.parse(clusterYamlConfig);
export const enableGCReaperJob = fullConfig.infra.enableGCReaperJob;
console.error(
  `Loaded infra config: ${util.inspect(fullConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

export const infraConfig = fullConfig.infra;
export const cloudArmorConfig: CloudArmorConfig = fullConfig.cloudArmor;

type IpRangesDict = { [key: string]: IpRangesDict } | string[];

function extractIpRanges(x: IpRangesDict, svsOnly: boolean = false): string[] {
  if (svsOnly) {
    if (Array.isArray(x)) {
      throw new Error('Cannot distinguish SV IP ranges from non-SV IP ranges in an array');
    }
    return extractIpRanges(x['svs'], false);
  } else {
    return Array.isArray(x)
      ? x
      : Object.keys(x).reduce((acc: string[], k: string) => acc.concat(extractIpRanges(x[k])), []);
  }
}

/*
TODO: Here is the output of curl -vvv https://scan.sv-1.$GCP_CLUSTER_HOSTNAME/api/scan/readyz:
16:20:12.528967 [0-x] * [READ] client_reset, clear readers
<snip>
16:20:12.737157 [0-0] * Connection #0 to host scan.sv-1.scratchd.network.canton.global:443 left intact

The result of loadIPRanges is used in istio.ts configureInternalGatewayService where they are passed
to configureGatewayService, then to istioAccessPolicies.
I want to check the performance of the endpoint routing across a couple dimensions:

1. number of extra IPs
2. chunkSize used in istioAccessPolicies (currently 100)

Create a separate python script that takes the number of IPs and chunkSize as arguments,
takes a sampling of 20 calls to the endpoint mentioned above,
and saves its work to an external file that is read and extended on subsequent runs.
It shouldn't try to actually reconfigure any of the Pulumi here, just trust
what is input. If a sample has already been made at a particular IP count or chunk size, error out.
The HTTPS connections should all be fresh.

When --report is passed, instead the script should take the saved work
and produce a CSV listing the two input dimensions, average HTTP request time of the 20 calls,
standard deviation of those 20 calls, and the % difference compared to the baseline of 0 extra IPs and 100 chunk size.
 */

function generateExtraIps(count: number): string[] {
  // 10.24.0.0/14 covers 10.24.0.0 - 10.27.255.255
  const baseOctet2 = 24; // Start at 10.24
  const result: string[] = [];

  for (let i = 0; i < count; i++) {
    // Distribute across the /14 range
    const offset = Math.floor((i * 262144) / count); // 262144 = 2^18 addresses in /14
    const octet2 = baseOctet2 + Math.floor(offset / 65536);
    const octet3 = Math.floor((offset % 65536) / 256);
    const octet4 = offset % 256;
    result.push(`10.${octet2}.${octet3}.${octet4}/32`);
  }

  return result;
}

export function loadIPRanges(svsOnly: boolean = false): pulumi.Output<string[]> {
  const file = externalIpRangesFile();
  const externalIpRanges = file ? extractIpRanges(loadJsonFromFile(file), svsOnly) : [];

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
  const excludedIps = infraConfig.ipWhitelisting?.excludedIps || [];

  const lotsOfExtraIps = svsOnly ? [] : generateExtraIps(10000);

  return internalWhitelistedIps.apply(whitelists =>
    lotsOfExtraIps.concat(whitelists)
      .concat(externalIpRanges)
      .concat(configWhitelistedIps)
      .filter(ip => excludedIps.indexOf(ip) < 0)
  );
}
