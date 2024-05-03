// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  process.env.GOOGLE_CREDENTIALS = 's3cr3t';
  process.env.SLACK_ACCESS_TOKEN = 's3cr3t';
  process.env.CN_DEPLOYMENT_FLUX_REF = 'refs/heads/releases/0.1.x';
  process.env.SLACK_DEPLOYMENT_ALERT_NOTIFICATION_CHANNEL = 'slack_channel';
  process.env.GITHUB_TOKEN = 's3cr3t';
  process.env.ARTIFACTORY_USER = 'art_user';
  process.env.ARTIFACTORY_PASSWORD = 's3cr3t';
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const deployment: typeof import('./src/index') = await import('./src/index');
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
