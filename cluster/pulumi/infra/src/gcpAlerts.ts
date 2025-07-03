// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import {
  CLUSTER_BASENAME,
  CLUSTER_NAME,
  conditionalString,
  config,
  isDevNet,
  isMainNet,
} from 'splice-pulumi-common';

import { slackToken } from './alertings';
import { monitoringConfig } from './config';

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');
const disableReplayWarnings = config.envFlag('DISABLE_REPLAY_WARNINGS');

export function getNotificationChannel(
  name: string = `${CLUSTER_BASENAME} Slack Alert Notification Channel`
): gcp.monitoring.NotificationChannel {
  const slackAlertNotificationChannel =
    config.optionalEnv('SLACK_ALERT_NOTIFICATION_CHANNEL_FULL_NAME') ||
    'team-canton-network-internal-alerts';
  return new gcp.monitoring.NotificationChannel(slackAlertNotificationChannel, {
    displayName: name,
    type: 'slack',
    labels: {
      channel_name: `#${slackAlertNotificationChannel}`,
    },
    sensitiveLabels: {
      authToken: slackToken(),
    },
  });
}

export function installGcpLoggingAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const logWarningsMetric = new gcp.logging.Metric('log_warnings', {
    name: `log_warnings_${CLUSTER_BASENAME}`,
    description: 'Logs with a severity level of warning or above',
    filter: `resource.labels.cluster_name="${CLUSTER_NAME}"
${monitoringConfig.alerting.logAlerts.shared}
${monitoringConfig.alerting.logAlerts.clusterSpecific || ''}
${conditionalString(
  // making this condition more complicated causes GCP to be unable to parse the query because there's too many filters
  isDevNet,
  `-- TODO(hyperledger-labs/splice#447): remove this once configured cardinality is respected
  -(jsonPayload.message="Instrument splice.trigger.latency.duration.seconds has exceeded the maximum allowed cardinality (1999).")
`
)}`,
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
    alertStrategy: {
      autoClose: '3600s',
      notificationChannelStrategies: [
        {
          notificationChannelNames: [notificationChannel.name],
          renotifyInterval: `${4 * 60 * 60}s`, // 4 hours
        },
      ],
    },
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
    alertStrategy: {
      autoClose: '3600s',
      notificationChannelStrategies: [
        {
          notificationChannelNames: [notificationChannel.name],
          renotifyInterval: `${4 * 60 * 60}s`, // 4 hours
        },
      ],
    },
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
    alertStrategy: {
      autoClose: '3600s',
      notificationChannelStrategies: [
        {
          notificationChannelNames: [notificationChannel.name],
          renotifyInterval: `${4 * 60 * 60}s`, // 4 hours
        },
      ],
    },
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
