// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { config } from '@canton-network/splice-pulumi-common';
import {
  CloudArmorConfig,
  CloudArmorConfigSchema,
} from '@canton-network/splice-pulumi-common/src/config/cloudArmorConfig';
import { clusterYamlConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import util from 'node:util';
import { z } from 'zod';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');

export const clusterHostname = config.requireEnv('GCP_CLUSTER_HOSTNAME');
export const clusterBaseDomain = clusterHostname.split('.')[0];

export const gcpDnsProject = config.requireEnv('GCP_DNS_PROJECT');

export const flowControlConfigSchema = z.object({
  initialStreamWindowSize: z.int(),
  initialConnectionWindowSize: z.int(),
  ports: z.array(z.number().int().positive()),
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
    gkeGateway: z
      .object({
        proxyForIstioHttp: z.boolean(),
      })
      .strict(),
    istio: z
      .object({
        enableIngressAccessLogging: z.boolean(),
        enableClusterAccessLogging: z.boolean().default(false),
        enablePublicTokenRegistry: z.boolean().default(false),
        istiodValues: z.object({}).catchall(z.any()).default({}),
        flowControl: z.object({
          // public APIs like the sequencer
          public: flowControlConfigSchema,
          // internal APIs like the participant
          internal: flowControlConfigSchema,
        }),
      })
      .strict(),
    enableSweetSecurity: z.boolean().default(false),
    extraCustomResources: z.object({}).catchall(z.any()).default({}),
  }),
  cloudArmor: CloudArmorConfigSchema,
});

export type Config = z.infer<typeof InfraConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = InfraConfigSchema.parse(clusterYamlConfig);
export const enableGCReaperJob = fullConfig.infra.enableGCReaperJob;
void pulumi.log.debug(
  `Loaded infra config: ${util.inspect(fullConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

export const infraConfig = fullConfig.infra;
export const cloudArmorConfig: CloudArmorConfig = fullConfig.cloudArmor;
