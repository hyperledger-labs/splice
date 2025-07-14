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
    filter: `severity>=WARNING
resource.type="k8s_container"
resource.labels.cluster_name="${CLUSTER_NAME}"
-- Note that we ignore the validator runbook. This is because we reset it periodically, which sometimes produces noise.
resource.labels.namespace_name=~"sv|validator1|multi-validator|splitwell"
-(resource.labels.container_name=~"participant" AND jsonPayload.message=~"Instrument .* has recorded multiple values for the same attributes.")
-- https://github.com/DACH-NY/canton-network-node/issues/10475
-(resource.labels.container_name="cometbft" AND
  (   jsonPayload.err=~"\\Aerror adding vote\\z|\\Aalready stopped\\z|use of closed network connection"
   OR jsonPayload._msg=~"\\A(Stopping peer for error|Stopped accept routine, as transport is closed|Failed to write PacketMsg|Connection failed @ sendRoutine)\\z"
   OR jsonPayload.error="already stopped"
   OR textPayload="cp: not replacing '/cometbft/data/priv_validator_state.json'"
   OR (jsonPayload._msg="Error stopping connection" AND jsonPayload.err="already stopped")
   OR jsonPayload._msg="Error adding peer to new bucket"))
-- execution context overload
-jsonPayload.message=~"Task runner canton-env-ec is .* overloaded"
-- on startup
-textPayload=~"Picked up JAVA_TOOL_OPTIONS:"
-- \\A and \\z anchor a search (=~) at beginning/end of string, respectively
-- regex is significantly faster than OR; gcp docs themselves recommend
-- regex-based factoring
-resource.labels.container_name=~"\\A(ans|wallet|scan|sv|splitwell)-web-ui\\z"
-- sequencer down
-(resource.labels.namespace_name=~"validator|splitwell"
  AND resource.labels.container_name=~"participant"
  AND jsonPayload.message=~"SEQUENCER_SUBSCRIPTION_LOST|Request failed for sequencer|Sequencer shutting down|Submission timed out|Response message for request .* timed out |periodic acknowledgement failed|Token refresh failed with Status{code=UNAVAILABLE")
-(resource.labels.container_name="postgres-exporter" AND jsonPayload.msg=~"Error loading config|Excluded databases")
-jsonPayload.message=~"UnknownHostException"
-(resource.labels.container_name=~"participant|mediator" AND jsonPayload.message=~"Late processing \\(or clock skew\\) of batch")
-(resource.labels.container_name="sequencer" AND jsonPayload.stack_trace=~"UnresolvedAddressException")
-(resource.labels.container_name="sequencer-pg" AND
  ("checkpoints are occurring too frequently" OR "Consider increasing the configuration parameter \\"max_wal_size\\"."))
-(resource.labels.container_name=~"participant" AND
  jsonPayload.message=~"SYNC_SERVICE_ALARM.*Received a request.*where the view.*has (missing|extra) recipients|LOCAL_VERDICT_MALFORMED_PAYLOAD.*Rejected transaction due to malformed payload within views.*WrongRecipients|channel.*shutdown did not complete gracefully in allotted|LOCAL_VERDICT_FAILED_MODEL_CONFORMANCE_CHECK.*: UnvettedPackages")
-(resource.labels.container_name="mediator" AND
  jsonPayload.message=~"MEDIATOR_RECEIVED_MALFORMED_MESSAGE.*(Reason: (Missing root hash message for informee participants|Superfluous root hash message)|Received a (mediator|confirmation) response.*with an invalid root hash)")
-(jsonPayload.logger_name=~"c.d.n.a.AdminAuthExtractor:" AND jsonPayload.message=~"Authorization Failed")
-(jsonPayload.level="error" AND jsonPayload.msg=~"/readyz")
-- The prometheus export server does not wait for any ongoing requests when shutting down https://github.com/prometheus/client_java/issues/938
-jsonPayload.message="The Prometheus metrics HTTPServer caught an Exception while trying to send the metrics response."
-- istio-proxy is spammy with warnings
-(resource.labels.container_name="istio-proxy" AND severity<ERROR)
-resource.labels.container_name="postgres"
-(resource.labels.container_name=~"postgres" AND resource.labels.namespace_name="multi-validator")
-- TODO(DACH-NY/canton-network-internal#412): Remove this once we have improved our sv onboarding logic
-(resource.labels.container_name="sv-app" AND jsonPayload.stack_trace=~"io.grpc.StatusRuntimeException: FAILED_PRECONDITION: UNHANDLED_EXCEPTION.*SV party has not yet operated a node")
-- TODO(#695): Don't just ignore this - investigate!
-(resource.labels.container_name="splitwell-app" AND jsonPayload.message=~"Waiting for domain Domain 'global' to be connected has not completed after")
-- TODO(#911): Our apps can't handle ingesting bursts of transactions after delays due to the record order publisher
-(jsonPayload.message=~"signalWhenIngested.* has not completed after .* milliseconds")
${conditionalString(
  isDevNet,
  "-- TODO(DACH-NY/canton-network-internal#475): Failing for all kinds of sequencer and CometBFT-related reasons; let's reevaluate on Canton 3.3\n" +
    '-(resource.labels.container_name="multi-validator" AND jsonPayload.message=~"wallet/transfer-offers.* resulted in a timeout")\n' +
    '-- TODO(#979): Can happen due to random disconnects/reconnects but also in other contexts\n' +
    '-(resource.labels.container_name="multi-participant" AND jsonPayload.message=~"The sequencer clock timestamp.*is already past the max sequencing time")'
)}
${conditionalString(
  !isMainNet,
  '-- TODO(DACH-NY/canton-network-node#17025): Stop ignoring these again once we have topology-aware package selection\n' +
    '-(jsonPayload."span-name"="MergeValidatorLicenseContractsTrigger" AND (severity=WARNING OR "has not vetted"))\n' +
    '-(jsonPayload."error-code"=~"ACS_COMMITMENT_MISMATCH" AND jsonPayload.remote=~"tw-cn-testnet-participant")'
)}
${conditionalString(
  enableChaosMesh,
  '-(resource.labels.namespace_name="multi-validator" AND jsonPayload.message=~"SEQUENCER_SUBSCRIPTION_LOST")'
)}
${conditionalString(
  disableReplayWarnings,
  '-(resource.labels.container_name=~"participant" AND (' +
    'jsonPayload.message=~"LOCAL_VERDICT_CREATES_EXISTING_CONTRACTS.*Rejected transaction would create contract\\(s\\) that already exist"' +
    ' OR ' +
    'jsonPayload.message=~"LOCAL_VERDICT_MALFORMED_REQUEST.*belongs to a replayed transaction"' +
    '))'
)}
${conditionalString(
  !isMainNet && !isDevNet,
  `-- TODO(DACH-NY/canton-network-node#19192): suppressed faulty validator warnings until timestamp
-(resource.labels.container_name="participant"
  AND resource.labels.namespace_name="sv-1"
  AND jsonPayload.message=~"ACS_COMMITMENT_MISMATCH"
  AND jsonPayload.remote=~"sender = PAR::tw-cn-testnet-participant-1::122051b3a160"
  AND timestamp <= "2025-05-14T09:00:00.000Z")
`
)}
${conditionalString(
  isMainNet,
  `-- TODO(DACH-NY/cn-test-failures#4768): suppressed faulty validator warnings until timestamp
-(resource.labels.container_name="participant"
  AND (
    jsonPayload.remote=~"sender = PAR::tw-cn-mainnet-participant-1::1220bc64ba15"
    OR jsonPayload.remote=~"sender = PAR::northisland-prod1::12204ef1928f"
    OR jsonPayload.remote=~"sender = PAR::Lukka-Inc-prod-2::1220728cfb80"
  )
  AND timestamp <= "2025-07-28T00:00:00.000Z")
`
)}
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
