// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import {
  CLUSTER_BASENAME,
  CLUSTER_NAME,
  conditionalString,
  config,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { slackAlertNotificationChannel, slackToken } from './alertings';
import { type GcpQuotaAlertsConfig, monitoringConfig } from './config';

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

function ensureTrailingNewline(s: string): string {
  return s.endsWith('\n') ? s : `${s}\n`;
}

// Monitoring filter limited to 2048 chars: https://cloud.google.com/monitoring/api/v3/filters
function assertFilterLength(filter: string): string {
  if (filter.length > 2048) {
    throw new Error(
      `${CLUSTER_BASENAME} monitoring filter is ${filter.length} chars; >2048 char limit`
    );
  }
  return filter;
}

export function getNotificationChannel(
  name: string = `${CLUSTER_BASENAME} Slack Alert Notification Channel`
): gcp.monitoring.NotificationChannel | undefined {
  const channelSlackName =
    slackAlertNotificationChannel &&
    config.requireEnv('SLACK_ALERT_NOTIFICATION_CHANNEL_FULL_NAME');
  return channelSlackName
    ? new gcp.monitoring.NotificationChannel(channelSlackName, {
        displayName: name,
        type: 'slack',
        labels: {
          channel_name: `#${channelSlackName}`,
        },
        sensitiveLabels: {
          authToken: slackToken(),
        },
      })
    : undefined;
}

function getAlertStrategy(notificationChannel: gcp.monitoring.NotificationChannel) {
  return {
    autoClose: '3600s',
    notificationChannelStrategies: [
      {
        notificationChannelNames: [notificationChannel.name],
        renotifyInterval: `${4 * 60 * 60}s`, // 4 hours
      },
    ],
  };
}

export function installGcpLoggingAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const logAlerts = monitoringConfig.alerting.logAlerts;
  const logAlertsString = `resource.labels.cluster_name="${CLUSTER_NAME}"
${Object.keys(logAlerts)
  .sort()
  .map(k => ensureTrailingNewline(logAlerts[k]))
  .join('')}`;
  if (logAlertsString.length > 20000) {
    // LQL limited to 20k: https://cloud.google.com/logging/quotas#log-based-metrics
    throw new Error(
      `${CLUSTER_BASENAME} log alerts string is ${logAlertsString.length} chars; >20000 char limit`
    );
  }

  const logWarningsMetric = new gcp.logging.Metric('log_warnings', {
    name: `log_warnings_${CLUSTER_BASENAME}`,
    description: 'Logs with a severity level of warning or above',
    filter: logAlertsString,
    labelExtractors: {
      cluster: 'EXTRACT(resource.labels.cluster_name)',
      namespace: 'EXTRACT(resource.labels.namespace_name)',
    },
    metricDescriptor: {
      labels: [
        {
          description: 'Pod namespace',
          key: 'namespace',
        },
        {
          description: 'Cluster name',
          key: 'cluster',
        },
      ],
      metricKind: 'DELTA',
      valueType: 'INT64',
    },
  });

  const alertCount = enableChaosMesh ? 50 : 1;
  const displayName = `Log warnings and errors > ${alertCount} ${CLUSTER_BASENAME}`;
  new gcp.monitoring.AlertPolicy('logsAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    conditions: [
      {
        conditionThreshold: {
          aggregations: [
            {
              //query period
              // if the chaos mesh is enabled we expand the query period to 1 hour to avoid false positives when the mesh is running
              alignmentPeriod: enableChaosMesh ? '3600s' : '600s',
              crossSeriesReducer: 'REDUCE_SUM',
              groupByFields: ['metric.label.cluster'],
              perSeriesAligner: 'ALIGN_SUM',
            },
          ],
          comparison: 'COMPARISON_GT',
          //retest period
          duration: '300s',
          filter: pulumi.interpolate`resource.type="k8s_container" ${conditionalString(
            enableChaosMesh,
            'AND resource.labels.namespace_name != "sv-4" '
          )} AND metric.type = "logging.googleapis.com/user/${logWarningsMetric.name}"`,
          trigger: {
            count: alertCount,
          },
        },
        displayName: displayName,
      },
    ],
    displayName: displayName,
    notificationChannels: [notificationChannel.name],
  });
}

export function installLoggedSecretsAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const loggedSecretsFilter = monitoringConfig.alerting.loggedSecretsFilter!;
  const filter = `resource.labels.cluster_name="${CLUSTER_NAME}"
${ensureTrailingNewline(loggedSecretsFilter)}`;

  const loggedSecretsMetric = new gcp.logging.Metric('logged_secrets', {
    name: `logged_secrets_${CLUSTER_BASENAME}`,
    description: 'Logs containing secrets (JWTs, Bearer tokens, passwords, etc.)',
    filter: filter,
    labelExtractors: {
      cluster: 'EXTRACT(resource.labels.cluster_name)',
      namespace: 'EXTRACT(resource.labels.namespace_name)',
    },
    metricDescriptor: {
      labels: [
        {
          description: 'Pod namespace',
          key: 'namespace',
        },
        {
          description: 'Cluster name',
          key: 'cluster',
        },
      ],
      metricKind: 'DELTA',
      valueType: 'INT64',
    },
  });

  const displayName = `Logged secrets detected in ${CLUSTER_BASENAME}`;
  new gcp.monitoring.AlertPolicy('loggedSecretsAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    conditions: [
      {
        conditionThreshold: {
          aggregations: [
            {
              //query period
              alignmentPeriod: '600s',
              crossSeriesReducer: 'REDUCE_SUM',
              groupByFields: ['metric.label.cluster'],
              perSeriesAligner: 'ALIGN_SUM',
            },
          ],
          comparison: 'COMPARISON_GT',
          // No retest period -- any secret in logs is critical
          duration: '0s',
          filter: pulumi.interpolate`resource.type="k8s_container" AND metric.type = "logging.googleapis.com/user/${loggedSecretsMetric.name}"`,
          trigger: {
            count: 1,
          },
        },
        displayName: displayName,
      },
    ],
    displayName: displayName,
    notificationChannels: [notificationChannel.name],
  });
}

// https://cloud.google.com/kubernetes-engine/docs/concepts/cluster-upgrades#control_plane_upgrade_logs
export function installClusterMaintenanceUpdateAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const logGkeClusterUpdate = new gcp.logging.Metric('log_gke_cluster_update', {
    name: `log_gke_cluster_update_${CLUSTER_BASENAME}`,
    description: 'Logs with ClusterUpdate events',
    filter: `
resource.labels.cluster_name="${CLUSTER_NAME}"
resource.type=~"(gke_cluster|gke_nodepool)"
jsonPayload.state=~"STARTED"`,
    labelExtractors: {
      cluster: 'EXTRACT(resource.labels.cluster_name)',
    },
    metricDescriptor: {
      labels: [
        {
          description: 'Cluster name',
          key: 'cluster',
        },
      ],
      metricKind: 'DELTA',
      valueType: 'INT64',
    },
  });

  const displayName = `Cluster ${CLUSTER_BASENAME} is being updated`;
  new gcp.monitoring.AlertPolicy('updateClusterAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    conditions: [
      {
        conditionThreshold: {
          aggregations: [
            {
              //query period
              alignmentPeriod: '600s',
              crossSeriesReducer: 'REDUCE_SUM',
              groupByFields: ['metric.label.cluster'],
              perSeriesAligner: 'ALIGN_SUM',
            },
          ],
          comparison: 'COMPARISON_GT',
          //retest period
          duration: '60s',
          filter: pulumi.interpolate`resource.type="global" AND metric.type = "logging.googleapis.com/user/${logGkeClusterUpdate.name}"`,
          trigger: {
            count: 1,
          },
        },
        displayName: displayName,
      },
    ],
    displayName: displayName,
    notificationChannels: [notificationChannel.name],
  });
}

export function installCloudSQLMaintenanceUpdateAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const logGkeCloudSQLUpdate = new gcp.logging.Metric('log_gke_cloudsql_update', {
    name: `log_gke_cloudsql_update_${CLUSTER_BASENAME}`,
    description: 'Logs with cloudsql databases events',
    filter: `
resource.type="cloudsql_database"
"terminating connection due to administrator command" OR "the database system is shutting down"`,
  });

  const displayName = `Possible CloudSQL maintenance going on in ${CLUSTER_BASENAME}`;
  new gcp.monitoring.AlertPolicy('updateCloudSQLAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    conditions: [
      {
        conditionThreshold: {
          aggregations: [
            {
              //query period
              alignmentPeriod: '600s',
              crossSeriesReducer: 'REDUCE_SUM',
              perSeriesAligner: 'ALIGN_SUM',
            },
          ],
          comparison: 'COMPARISON_GT',
          //retest period
          duration: '60s',
          filter: pulumi.interpolate`resource.type="cloudsql_database" AND metric.type = "logging.googleapis.com/user/${logGkeCloudSQLUpdate.name}"`,
          trigger: {
            count: 1,
          },
        },
        displayName: displayName,
      },
    ],
    displayName: displayName,
    notificationChannels: [notificationChannel.name],
  });
}

export function installGcpQuotaAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel,
  gcpQuotasConfig: GcpQuotaAlertsConfig
): void {
  const quotaUsageThreshold = 0.9;
  const quotaUsageThresholdPercent = quotaUsageThreshold * 100;
  const excludedMetrics = gcpQuotasConfig.excludedMetrics;

  // Build exclusion fragments for threshold filters and PromQL queries.
  // excludedMetrics applies to all alerts; excludedApproachingMetrics only to the >90% alerts.
  // Cloud Monitoring filters only support = and != (no regex), so we use one != per metric.
  const thresholdExclusion = excludedMetrics
    .map(m => ` AND metric.label.quota_metric != "${m}"`)
    .join('');

  const approachingExcluded = [...excludedMetrics, ...gcpQuotasConfig.excludedApproachingMetrics];
  const approachingExclusionRegex =
    approachingExcluded.length > 0 ? approachingExcluded.join('|') : null;
  const promqlExclusion =
    approachingExclusionRegex !== null ? `, quota_metric!~"${approachingExclusionRegex}"` : '';

  const { rollingWindowSeconds, retestWindowSeconds } = gcpQuotasConfig;
  const rollingWindowDuration = `${rollingWindowSeconds}s`;
  const retestWindowDuration = `${retestWindowSeconds}s`;

  const baseArgs: Pick<
    gcp.monitoring.AlertPolicyArgs,
    'alertStrategy' | 'combiner' | 'notificationChannels' | 'userLabels'
  > = {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    notificationChannels: [notificationChannel.name],
    userLabels: { cluster: CLUSTER_BASENAME },
    // severity: 'SEVERITY_UNSPECIFIED', // "Policy Severity Level"
  };

  new gcp.monitoring.AlertPolicy('quotaExceededAlert', {
    ...baseArgs,
    displayName: `Quota Exceeded in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `Quota \${metric.label.quota_metric} exceeded in ${CLUSTER_BASENAME}`,
      content: `The quota "\${metric.display_name}" (\${metric.label.quota_metric}) has been exceeded in cluster ${CLUSTER_BASENAME}.`,
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        // "Quota Full" (Exceeded right now)
        displayName: `Quota Exceeded in ${CLUSTER_BASENAME}`,
        conditionThreshold: {
          aggregations: [
            {
              alignmentPeriod: rollingWindowDuration, // "Rolling window"
              crossSeriesReducer: 'REDUCE_SUM',
              groupByFields: ['metric.label.quota_metric'],
              perSeriesAligner: 'ALIGN_COUNT_TRUE',
            },
          ],
          comparison: 'COMPARISON_GT',
          duration: retestWindowDuration, // "Retest window"
          filter: assertFilterLength(
            `resource.type="consumer_quota" AND metric.type="serviceruntime.googleapis.com/quota/exceeded"${thresholdExclusion}`
          ),
          trigger: {
            count: 1,
          },
        },
      },
    ],
  });

  // Allocation quotas track consumed capacity (for example CPUs, IPs, disk)
  // against fixed limits, while rate quotas track request throughput over time
  // windows (for example API calls per minute). These are tracked separately so
  // we have separate alerts for them

  const windowSettings: Pick<
    gcp.types.input.monitoring.AlertPolicyConditionConditionPrometheusQueryLanguage,
    'duration' | 'evaluationInterval'
  > = {
    duration: retestWindowDuration, // "Retest window"
    evaluationInterval: '30s', // "Evaluation interval"
  };

  new gcp.monitoring.AlertPolicy('quotaAllocationAlert', {
    ...baseArgs,
    displayName: `Allocation Quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `Allocation quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
      content: [
        `An allocation quota (CPUs, Static IPs, Disk Space, etc.) is >${quotaUsageThresholdPercent}% utilized in **${CLUSTER_BASENAME}**.`,
        'Check the incident details, chart under "Alert Metrics", for the specific quota.',
      ].join('\n\n'),
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        // Tracks resources like CPUs, Static IPs, Disk Space
        displayName: `Allocation Quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
        conditionPrometheusQueryLanguage: {
          query: `
            avg_over_time(serviceruntime_googleapis_com:quota_allocation_usage{monitored_resource="consumer_quota"${promqlExclusion}}[${rollingWindowDuration}])
            / ignoring(limit_name) group_right()
            (serviceruntime_googleapis_com:quota_limit{monitored_resource="consumer_quota"${promqlExclusion}} > 0)
            > ${quotaUsageThreshold}
          `,
          ...windowSettings,
        },
      },
    ],
  });

  new gcp.monitoring.AlertPolicy('quotaRateAlert', {
    ...baseArgs,
    displayName: `Rate Quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `Rate quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
      content: [
        `A rate quota (API requests per minute, HSM operations, etc.) is >${quotaUsageThresholdPercent}% utilized in **${CLUSTER_BASENAME}**.`,
        'Check the incident details, chart under "Alert Metrics", for the specific quota.',
      ].join('\n\n'),
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        // Tracks API requests, HSM operations per minute, etc.
        displayName: `Rate Quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
        conditionPrometheusQueryLanguage: {
          query: `
            avg_over_time(serviceruntime_googleapis_com:quota_rate_net_usage{monitored_resource="consumer_quota"${promqlExclusion}}[${rollingWindowDuration}])
            / ignoring(limit_name) group_right()
            (serviceruntime_googleapis_com:quota_limit{monitored_resource="consumer_quota"${promqlExclusion}} > 0)
            > ${quotaUsageThreshold}
          `,
          ...windowSettings,
        },
      },
    ],
  });
}

export function installCloudSqlTxIdUtilizationAlert(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  new gcp.monitoring.AlertPolicy('txIdUtilizationAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    notificationChannels: [notificationChannel.name],
    displayName: `High Transaction ID Utilization in ${CLUSTER_BASENAME}`,
    conditions: [
      {
        displayName: `Cloud SQL Database - Transaction ID utilization ${CLUSTER_BASENAME}`,
        conditionThreshold: {
          filter:
            'resource.type = "cloudsql_database" AND metric.type = "cloudsql.googleapis.com/database/postgresql/transaction_id_utilization"',
          aggregations: [
            {
              alignmentPeriod: '3600s',
              crossSeriesReducer: 'REDUCE_NONE',
              perSeriesAligner: 'ALIGN_MIN',
            },
          ],
          comparison: 'COMPARISON_GT',
          duration: '21600s',
          trigger: {
            count: 1,
          },
          thresholdValue: 0.8,
        },
      },
    ],
  });
}
