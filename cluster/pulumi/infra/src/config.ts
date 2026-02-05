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

const MonitoringConfigSchema = z.object({
  alerting: z.object({
    enableNoDataAlerts: z.boolean(),
    alerts: z.object({
      pruning: z.object({
        participantRetentionDays: z.number(),
        sequencerRetentionDays: z.number(),
        mediatorRetentionDays: z.number(),
      }),
      ingestion: z.object({
        thresholdEntriesPerBatch: z.number(),
      }),
      delegatelessContention: z.object({
        thresholdPerNamespace: z.number(),
      }),
      trafficWaste: z.object({
        kilobytes: z.number(),
        overMinutes: z.number(),
        quantile: z.number(),
      }),
      confirmationRequests: z.object({
        total: z.object({
          rate: z.number(),
          overMinutes: z.number(),
        }),
        perMember: z.object({
          rate: z.number(),
          overMinutes: z.number(),
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
      svNames: z.array(z.string()).default([]),
      mediators: z.object({
        acknowledgementLagSeconds: z.number(),
      }),
    }),
    logAlerts: z.object({}).catchall(z.string()).default({}),
  }),
});
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
    prometheus: z.object({
      storageSize: z.string(),
      retentionDuration: z.string(),
      retentionSize: z.string(),
      installPrometheusPushgateway: z.boolean().default(false),
    }),
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
  monitoring: MonitoringConfigSchema,
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
export const monitoringConfig = fullConfig.monitoring;
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

  return internalWhitelistedIps.apply(whitelists =>
    whitelists
      .concat(externalIpRanges)
      .concat(configWhitelistedIps)
      .filter(ip => excludedIps.indexOf(ip) < 0)
  );
}
