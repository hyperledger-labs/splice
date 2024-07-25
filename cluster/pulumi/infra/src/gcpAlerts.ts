import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_BASENAME, CLUSTER_NAME, conditionalString, config } from 'cn-pulumi-common';

import { slackToken } from './alertings';

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

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
    filter: `severity>=WARNING
resource.type="k8s_container"
resource.labels.cluster_name="${CLUSTER_NAME}"
resource.labels.namespace_name=~"sv.*|validator.*|splitwell"
-(resource.labels.container_name=~".*participant" AND jsonPayload.message=~"Instrument .* has recorded multiple values for the same attributes.")
-- https://github.com/DACH-NY/canton-network-node/issues/10475
-(resource.labels.container_name="cometbft" AND jsonPayload.err="error adding vote")
-(resource.labels.container_name="cometbft" AND jsonPayload._msg="Stopping peer for error")
-(resource.labels.container_name="cometbft" AND jsonPayload._msg="Failed to write PacketMsg")
-(resource.labels.container_name="cometbft" AND jsonPayload._msg="Connection failed @ sendRoutine")
-- execution context overload
-jsonPayload.message=~"Task runner canton-env-ec is .* overloaded.*"
-- on startup
-textPayload=~"Picked up JAVA_TOOL_OPTIONS:.*"
-resource.labels.container_name="cns-web-ui"
-resource.labels.container_name="wallet-web-ui"
-resource.labels.container_name="scan-web-ui"
-resource.labels.container_name="sv-web-ui"
-resource.labels.container_name="splitwell-web-ui"
-(resource.labels.container_name="cometbft" AND textPayload="cp: not replacing '/cometbft/data/priv_validator_state.json'")
-- sequencer down
-(resource.labels.namespace_name=~"validator.*|splitwell" AND resource.labels.container_name=~".*participant" AND jsonPayload.message=~".*SEQUENCER_SUBSCRIPTION_LOST.*|Request failed for sequencer.*|Submission timed out.*|Response message for request .* timed out .*|periodic acknowledgement failed|Token refresh failed with Status{code=UNAVAILABLE")
-(resource.labels.container_name="postgres-exporter" AND jsonPayload.msg=~"Error loading config|Excluded databases")
-UnknownHostException
-"Late processing (or clock skew) of batch"
-UnresolvedAddressException
-(resource.labels.container_name="sequencer-pg" AND ("checkpoints are occurring too frequently" OR "Consider increasing the configuration parameter \\"max_wal_size\\"."))
-(resource.labels.container_name="cometbft" AND jsonPayload._msg="Error stopping connection" AND jsonPayload.err="already stopped")
-(resource.labels.container_name=~".*participant" AND jsonPayload.message=~"SYNC_SERVICE_ALARM.*Received a request.*where the view.*has missing recipients.*")
-(resource.labels.container_name=~".*participant" AND jsonPayload.message=~"SYNC_SERVICE_ALARM.*Received a request.*where the view.*has extra recipients.*")
-(resource.labels.container_name=~".*participant" AND jsonPayload.message=~"LOCAL_VERDICT_MALFORMED_PAYLOAD.*Rejected transaction due to malformed payload within views.*WrongRecipients")
-(resource.labels.container_name=~".*participant" AND jsonPayload.message=~"channel.*shutdown did not complete gracefully in allotted")
-(resource.labels.container_name=~".*participant" AND jsonPayload.message=~"LOCAL_VERDICT_FAILED_MODEL_CONFORMANCE_CHECK.*: UnvettedPackages")
-(resource.labels.container_name="mediator" AND jsonPayload.message=~"MEDIATOR_RECEIVED_MALFORMED_MESSAGE.*Reason: Missing root hash message for informee participants")
-(resource.labels.container_name="mediator" AND jsonPayload.message=~"MEDIATOR_RECEIVED_MALFORMED_MESSAGE.*Reason: Superfluous root hash message")
-(resource.labels.container_name="mediator" AND jsonPayload.message=~"MEDIATOR_RECEIVED_MALFORMED_MESSAGE.*Received a mediator response.*with an invalid root hash")
-(resource.labels.container_name="mediator" AND jsonPayload.message=~"MEDIATOR_RECEIVED_MALFORMED_MESSAGE.*Received a confirmation response.*with an invalid root hash")
-(jsonPayload.logger_name=~"c.d.n.a.AdminAuthExtractor:.*" AND jsonPayload.message=~".*Authorization Failed.*")
-(jsonPayload.level="error" AND jsonPayload.msg=~".*/readyz")
-- The prometheus export server does not wait for any ongoing requests when shutting down https://github.com/prometheus/client_java/issues/938
-jsonPayload.message="The Prometheus metrics HTTPServer caught an Exception while trying to send the metrics response."
-- istio-proxy is spammy with warnings
-(resource.labels.container_name="istio-proxy" AND severity<ERROR)
${conditionalString(
  enableChaosMesh,
  '-(resource.labels.namespace_name="multi-validator" AND "SEQUENCER_SUBSCRIPTION_LOST")'
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
export function installMaintenanceUpdateAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const logGkeClusterUpdate = new gcp.logging.Metric('log_gke_cluster_update', {
    name: `log_gke_cluster_update_${CLUSTER_BASENAME}`,
    description: 'Logs with ClusterUpdate events',
    filter: `
resource.labels.cluster_name="${CLUSTER_NAME}"
resource.type=~"(gke_cluster|gke_nodepool)"
protoPayload.metadata.operationType=~"(UPDATE_CLUSTER|UPGRADE_MASTER|UPGRADE_NODES)"`,
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
  new gcp.monitoring.AlertPolicy('updateAlert', {
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
