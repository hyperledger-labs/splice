// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterProdLike, config } from 'splice-pulumi-common';
import { spliceEnvConfig } from 'splice-pulumi-common/src/config/envConfig';

export const enableAlerts = clusterProdLike || config.envFlag('ENABLE_ALERTS_FOR_TESTING');
export const slackAlertNotificationChannel =
  config.optionalEnv('SLACK_ALERT_NOTIFICATION_CHANNEL') || 'C064MTNQT88';
// empty or missing value for the high prio notification channel disables high prio notifications
export const slackHighPrioAlertNotificationChannel =
  config.optionalEnv('SLACK_HIGH_PRIO_ALERT_NOTIFICATION_CHANNEL') || '';
export const enableAlertEmailToSupportTeam =
  config.envFlag('ENABLE_ALERT_EMAIL_TO_SUPPORT_TEAM') || false;
export const supportTeamEmail = config.optionalEnv('SUPPORT_TEAM_EMAIL');
export const grafanaSmtpHost = config.optionalEnv('GRAFANA_SMTP_HOST');

export function slackToken(): string {
  return config.requireEnv('SLACK_ACCESS_TOKEN');
}

export const clusterIsResetPeriodically = spliceEnvConfig.envFlag('GCP_CLUSTER_RESET_PERIODICALLY');
export const enablePrometheusAlerts = config.envFlag(
  'ENABLE_PROMETHEUS_ALERTS',
  !clusterIsResetPeriodically
);
export const enableMiningRoundAlert = config.envFlag(
  'ENABLE_MINING_ROUND_ALERT',
  !clusterIsResetPeriodically
);
