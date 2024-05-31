import { config } from 'cn-pulumi-common';

export const enableAlerts = config.envFlag('GCP_CLUSTER_PROD_LIKE');
export const slackAlertNotificationChannel =
  config.optionalEnv('SLACK_ALERT_NOTIFICATION_CHANNEL') || 'C064MTNQT88';

export function slackToken(): string {
  return config.requireEnv('SLACK_ACCESS_TOKEN');
}
