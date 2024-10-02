import { config } from 'splice-pulumi-common';

export const enableAlerts =
  config.envFlag('GCP_CLUSTER_PROD_LIKE') || config.envFlag('ENABLE_ALERTS_FOR_TESTING');
export const slackAlertNotificationChannel =
  config.optionalEnv('SLACK_ALERT_NOTIFICATION_CHANNEL') || 'C064MTNQT88';
export const enableAlertEmailToSupportTeam =
  config.envFlag('ENABLE_ALERT_EMAIL_TO_SUPPORT_TEAM') || false;
export const supportTeamEmail = config.optionalEnv('SUPPORT_TEAM_EMAIL');
export const grafanaSmtpHost = config.optionalEnv('GRAFANA_SMTP_HOST');

export function slackToken(): string {
  return config.requireEnv('SLACK_ACCESS_TOKEN');
}

export const clusterIsBeingReset = config.envFlag('GCP_CLUSTER_RESET_PERIODICALLY');
export const enablePrometheusAlerts = config.envFlag(
  'ENABLE_PROMETHEUS_ALERTS',
  !clusterIsBeingReset
);
