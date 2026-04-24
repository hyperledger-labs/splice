// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterSubConfig } from '@lfdecentralizedtrust/splice-pulumi-common';
import { z } from 'zod';

const quotaMetricNameSchema = z
  .string()
  .regex(/^[a-zA-Z0-9.-]+\.[a-zA-Z0-9-]+\/[a-zA-Z0-9_./-]+$/, 'full GCP quota metric name');

// Prometheus duration format, e.g. "60s", "5m", "1h"
const prometheusDurationSchema = z
  .string()
  .regex(/^\d+[smh]$/, 'basic Prometheus duration (e.g. "60s", "5m", "1h")');

export function parseDurationToSeconds(d: string): number {
  const match = d.match(/^(\d+)([smh])$/);
  if (!match) {
    throw new Error(`Invalid duration: ${d}`);
  }
  const [, n, unit] = match;
  const multiplier = unit === 'h' ? 3600 : unit === 'm' ? 60 : 1;
  return parseInt(n, 10) * multiplier;
}

const GcpQuotasConfigSchema = z.object({
  // so existing overrides don't break
  enabled: z.literal(true).optional(),
  excludedMetrics: z.array(quotaMetricNameSchema),
  excludedApproachingMetrics: z.array(quotaMetricNameSchema),
  rollingWindow: prometheusDurationSchema.refine(v => parseDurationToSeconds(v) >= 60, {
    // this rule comes from GCP aggregations.alignmentPeriod on quota-exceeded
    message: 'must be at least 60s',
  }),
  retestWindow: prometheusDurationSchema.refine(
    v => {
      const seconds = parseDurationToSeconds(v);
      return seconds > 0 && seconds % 60 === 0;
    },
    {
      // this rule comes from GCP duration on quota-exceeded
      message: 'must be a positive multiple of 1m',
    }
  ),
});

const MonitoringConfigSchema = z
  .object({
    enableGrafanaServiceAccountToken: z.boolean(),
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
        deployment: z.object({
          pendingPeriodMinutes: z.number(),
        }),
        sequencerClientDelay: z.object({
          seconds: z.number(),
        }),
        acsCommitments: z.object({
          checkpointDelay: z.object({
            seconds: z.number(),
          }),
          completedDelay: z.object({
            seconds: z.number(),
          }),
          computeDuration: z.object({
            seconds: z.number(),
          }),
        }),
        acsSnapshots: z.object({
          saveLatencyThresholdSeconds: z.number(),
          updateLatencyThresholdSeconds: z.number(),
        }),
        sequencerRateLimits: z.object({
          rejectionRateThreshold: z.number(),
          circuitBreakerStateThreshold: z.number(),
        }),
        walletSweep: z.object({
          tolerance: z.number(),
        }),
        gcpQuotas: GcpQuotasConfigSchema,
        trafficBasedRewards: z.object({
          featuredAppRightsLimit: z.number(),
        }),
      }),
      logAlerts: z.object({}).catchall(z.string()).default({}),
      loggedSecretsFilter: z.string().optional(),
      muteTimeIntervals: z
        .array(
          z.object({
            name: z.string(),
            objectMatchers: z.array(z.tuple([z.string(), z.string(), z.string()])),
            startTime: z.string(), // UTC
            endTime: z.string(), // UTC
            weekdays: z.array(z.string()).optional(), // e.g. ['monday', 'tuesday:friday']
          })
        )
        .default([]),
    }),
  })
  .strict();

export const monitoringConfig = MonitoringConfigSchema.parse(clusterSubConfig('monitoring'));

export type GcpQuotaAlertsConfig = z.infer<typeof GcpQuotasConfigSchema>;

const PrometheusConfigSchema = z.object({
  prometheus: z.object({
    storageSize: z.string(),
    retentionDuration: z.string(),
    retentionSize: z.string(),
    installPrometheusPushgateway: z.boolean().default(false),
  }),
});

export const prometheusConfig = PrometheusConfigSchema.parse(clusterSubConfig('infra')).prometheus;
