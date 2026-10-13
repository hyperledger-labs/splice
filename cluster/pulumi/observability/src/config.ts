// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterSubConfig, SplicePostgresSchema } from '@canton-network/splice-pulumi-common';
import { CloudArmorConfigSchema } from '@canton-network/splice-pulumi-common/src/config/cloudArmorConfig';
import { z } from 'zod';

const quotaMetricNameSchema = z
  .string()
  .regex(/^[a-zA-Z0-9.-]+\.[a-zA-Z0-9-]+\/[a-zA-Z0-9_./-]+$/, 'full GCP quota metric name');

const GcpQuotasConfigSchema = z.object({
  // so existing overrides don't break
  enabled: z.literal(true).optional(),
  excludedMetrics: z.array(quotaMetricNameSchema),
  excludedApproachingMetrics: z.array(quotaMetricNameSchema),
  rollingWindowSeconds: z.number().int().min(60, {
    // this rule comes from GCP aggregations.alignmentPeriod on quota-exceeded
    message: 'must be at least 60s',
  }),
  retestWindowSeconds: z
    .number()
    .int()
    .positive()
    .refine(v => v % 60 === 0, {
      // this rule comes from GCP duration on quota-exceeded
      message: 'must be a positive multiple of 60s',
    }),
});

const NatPortUsageConfigSchema = z.object({
  thresholdPercent: z.number().min(0).max(100),
  droppedSentPacketsThreshold: z.number().min(0),
});

export type NatPortUsageConfig = z.infer<typeof NatPortUsageConfigSchema>;

// Threshold based alert on a request count: the requests are summed over windows of
// `alignmentPeriodSeconds` and the alert fires once such a window exceeds `threshold`
// for `durationSeconds` in a row.
const CloudArmorAlertConfigSchema = z
  .object({
    // Number of requests within one alignment period above which the alert fires;
    // 0 means a single request already alerts.
    threshold: z.number().min(0),
    // Length of the window the requests are summed over (GCP `alignmentPeriod`).
    alignmentPeriodSeconds: z
      .number()
      .int()
      .min(60)
      .refine(v => v % 60 === 0, { message: 'must be a multiple of 60s' })
      .default(300),
    // Retest window (GCP `duration`): how long the threshold has to be exceeded before
    // the alert fires. 0 fires on the first violating alignment period.
    durationSeconds: z.number().int().min(0).default(0),
  })
  .refine(c => c.durationSeconds % c.alignmentPeriodSeconds === 0, {
    // GCP requires the retest window to be a multiple of the alignment period
    message: 'durationSeconds must be 0 or a multiple of alignmentPeriodSeconds',
    path: ['durationSeconds'],
  });

export type CloudArmorAlertConfig = z.infer<typeof CloudArmorAlertConfigSchema>;

// Grafana alerts on the requests rejected by the Cloud Armor policy.
const CloudArmorAlertsConfigSchema = z.object({
  // Requests denied by any rule of the Cloud Armor policy (metric based).
  deniedRequests: CloudArmorAlertConfigSchema,
  // Requests matching a WAF (OWASP CRS) rule (log based).
  wafRejections: CloudArmorAlertConfigSchema.prefault({ threshold: 0 }),
  // Requests rejected by a per endpoint throttle rule (log based).
  throttleRejections: CloudArmorAlertConfigSchema.prefault({ threshold: 0 }),
});

export const cloudArmorConfig = CloudArmorConfigSchema.parse(clusterSubConfig('cloudArmor'));

const MuteTimeWindowSchema = z.object({
  times: z.array(
    z.object({
      startTime: z.string(), // UTC
      endTime: z.string(), // UTC
    })
  ),
  weekdays: z.array(z.string()).optional(), // e.g. ['monday', 'tuesday:friday']
});

const MuteTimeIntervalSchema = z.array(
  z.object({
    name: z.string(),
    objectMatchers: z.array(z.tuple([z.string(), z.string(), z.string()])),
    timeWindows: z.array(MuteTimeWindowSchema),
  })
);

const MonitoringConfigSchema = z
  .object({
    enableGrafanaServiceAccountToken: z.boolean(),
    grafanaPostgres: SplicePostgresSchema.default({ deployment: 'legacy-helm-chart' }),
    alerting: z.object({
      enableNoDataAlerts: z.boolean(),
      // routes more alerts than just "mining rounds are not advancing" to #team-canton-network-high-prio-prod-alerts
      enableExtraHighPrioAlerts: z.boolean().default(false),
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
        delegatelessNonLocalContention: z.object({
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
        dsoMissedConfirmations: z.object({
          // Fraction (0-1) of confirmation requests (as measured by the mediator) in
          // which the DSO party may miss its confirmation within the rolling window
          // before the alert fires.
          threshold: z.number(),
          // Rolling window (in minutes) over which the DSO party missed confirmation
          // rate is computed.
          windowMinutes: z.number(),
        }),
        spliceRateLimits: z.object({
          // Fraction (0-1) of a rate limiter's configured maximum rate above which the alert fires
          usageThreshold: z.number(),
          // Rejected requests per second, above which the rejection alert fires
          rejectionCountThreshold: z.number(),
          excludedLimiters: z.array(z.string()).default([]),
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
          usePv36Metrics: z.boolean().default(false),
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
        cantonBft: z.object({
          // Alert if the number of ingress requests queued in the BFT ordering
          // layer exceeds this threshold.
          mempoolMaxSizeThreshold: z.number(),
        }),
        scanConnectionDisagreement: z.object({
          // Fraction (0-1) of BFT consensus comparisons on a scan connection that may
          // return a response (successful or failed) disagreeing with the consensus
          // result before the success/failure alerts fire.
          alertThreshold: z.number(),
          // Requests (by their `request` label) to exclude from the scan connection
          // disagreement alerts. Matched as a regex against the `request` label.
          excludedRequests: z.array(z.string()).default([]),
          // Scan connections (by their `scan_connection` label) to exclude from the scan
          // connection disagreement alerts. Matched as a regex against the
          // `scan_connection` label.
          excludedConnections: z.array(z.string()).default([]),
          // Http status code (by their `http_status` label) to exclude from the disagreement alerts.
          // Matched as a regex against the `http_status` label.
          excludedHttpStatusCodes: z.array(z.string()).default([]),
        }),
        walletSweep: z.object({
          tolerance: z.number(),
        }),
        gcpQuotas: GcpQuotasConfigSchema,
        cloudArmor: CloudArmorAlertsConfigSchema.prefault({ deniedRequests: { threshold: 0 } }),
        natPortUsage: NatPortUsageConfigSchema.default({
          thresholdPercent: 80,
          // `default 30` because every once in a while (likely due to dynamic port allocation),
          // a few packets (less than 1/s) get dropped and getting alerted on it every time can be very noisy.
          droppedSentPacketsThreshold: 30,
        }),
        globalSynchronizerHealth: z.object({
          // Fraction (0-1) of sequenced confirmation requests that were discarded
          // (i.e., never processed by the mediator, e.g. due to CometBFT replays)
          // above which the alert fires.
          discardedConfirmationRequestsThreshold: z.number(),
          // Fraction (0-1) of confirmation requests that failed (as observed by the
          // mediator, over the last 30m) above which the alert fires.
          failedConfirmationRequestsThreshold: z.number(),
          // Fire when TPS (approved confirmation requests per second) over the last
          // 30m drops below this fraction of the previous 30m.
          tpsDropThreshold: z.number(),
        }),
        trafficBasedRewards: z.object({
          featuredAppRightsLimit: z.number(),
          verdictIngestionBatchSizeThreshold: z.number(),
          verdictIngestionBatchSizePendingPeriodMinutes: z.number(),
        }),
      }),
      logAlerts: z.object({}).catchall(z.string()).default({}),
      loggedSecretsFilter: z.string().optional(),
      muteTimeIntervals: MuteTimeIntervalSchema.default([]),
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
