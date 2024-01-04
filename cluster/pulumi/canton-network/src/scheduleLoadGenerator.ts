import {
  CLUSTER_BASENAME,
  envFlag,
  exactNamespace,
  installCNHelmChart,
  isDevNet,
  requireEnv,
} from 'cn-pulumi-common';

import { auth0Cfg } from './auth0cfg';

export function scheduleLoadGenerator(): void {
  if (envFlag('K6_ENABLE_LOAD_GENERATOR')) {
    const xns = exactNamespace('load-tester', true);

    const clusterHostname = `${CLUSTER_BASENAME}.network.canton.global`;

    // install loopback so the test can hit the wallet/validator API via its public DNS name
    const loopback = installCNHelmChart(
      xns,
      'loopback',
      'cn-cluster-loopback-gateway',
      {
        cluster: {
          basename: CLUSTER_BASENAME,
        },
      },
      { dependsOn: [xns.ns] }
    );

    // give pulumi + cluster time to stabilize before the first load test run starts.
    const minute = (new Date().getMinutes() + 10) % 60;
    // trigger the job once every hour, starting 10 mins from now
    const schedule = `${minute} * * * *`;

    const oauthDomain = `https://${auth0Cfg.auth0Domain}`;
    const oauthClientId = auth0Cfg.namespaceToUiToClientId?.validator1?.wallet;
    if (!oauthClientId) {
      throw new Error('Missing wallet UI ClientId for validator1');
    }
    const usersPassword = requireEnv('K6_USERS_PASSWORD');

    // use internal cluster hostnames for the prometheus endpoint
    const prometheusRw =
      'http://prometheus-prometheus.observability.svc.cluster.local:9090/api/v1/write';

    installCNHelmChart(
      xns,
      'load-tester',
      'cn-load-tester',
      {
        schedule,
        prometheusRw,
        config: JSON.stringify({
          isDevNet,
          usersPerValidator: 10,
          validators: [
            {
              walletBaseUrl: `https://wallet.validator1.${clusterHostname}`,
              auth: {
                oauthDomain,
                oauthClientId,
                usersPassword,
                managementApi: {
                  clientId: requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_ID'),
                  clientSecret: requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET'),
                },
                admin: {
                  email: process.env['K6_VALIDATOR_ADMIN_USERNAME'] || 'admin@validator1.com',
                  password: requireEnv('K6_VALIDATOR_ADMIN_PASSWORD'),
                },
              },
            },
          ],
          test: {
            duration: '58m',
            iterationsPerMinute: 60,
          },
        }),
      },
      { dependsOn: [loopback] }
    );
  } else {
    console.log('K6 load test is disabled for this cluster. Skipping...');
  }
}
