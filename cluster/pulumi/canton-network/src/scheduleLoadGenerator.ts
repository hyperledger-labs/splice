import {
  CLUSTER_BASENAME,
  envFlag,
  exactNamespace,
  installCNHelmChart,
  requireEnv,
} from 'cn-pulumi-common';

import { auth0Cfg } from './auth0cfg';

export function scheduleLoadGenerator(): void {
  if (envFlag('K6_ENABLE_LOAD_GENERATOR', false)) {
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

    const oauthDomain = `https://${auth0Cfg.auth0Domain}`;
    const oauthClientId = auth0Cfg.namespaceToUiClientId.validator1;
    const userCredentials = requireEnv('K6_USER_CREDENTIALS');

    // use internal cluster hostnames for the prometheus endpoint
    const prometheusRw =
      'http://prometheus-prometheus.observability.svc.cluster.local:9090/api/v1/write';

    installCNHelmChart(
      xns,
      'load-tester',
      'cn-load-tester',
      {
        schedule: `${minute} * * * *`, // trigger the job once every hour, starting 10 mins from now
        config: JSON.stringify({
          testDuration: '58m',
          walletBaseUrl: `https://wallet.validator1.${clusterHostname}`,
          auth: { oauthDomain, oauthClientId, userCredentials },
        }),
        prometheusRw,
      },
      { dependsOn: [loopback] }
    );
  } else {
    console.log('K6 load test is disabled for this cluster. Skipping...');
  }
}
