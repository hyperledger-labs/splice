import {
  Auth0Client,
  CLUSTER_BASENAME,
  envFlag,
  exactNamespace,
  generatePortSequence,
  installCNHelmChart,
  isDevNet,
  requireEnv,
} from 'cn-pulumi-common';

export function scheduleLoadGenerator(auth0Client: Auth0Client): void {
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

    const oauthDomain = `https://${auth0Client.getCfg().auth0Domain}`;
    const oauthClientId = auth0Client.getCfg().namespaceToUiToClientId?.validator1?.wallet;
    const usersPassword = requireEnv('K6_USERS_PASSWORD');

    // use internal cluster hostnames for the prometheus endpoint
    const prometheusRw =
      'http://prometheus-prometheus.observability.svc.cluster.local:9090/api/v1/write';

    const validator1 = {
      walletBaseUrl: `https://wallet.validator1.${clusterHostname}`,
      auth: {
        kind: 'oauth',
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
    };

    const multiValidatorConfigs = generatePortSequence(5000, 10, [{ name: '', id: 3 }]).map(
      (p, i) => ({
        walletBaseUrl: `http://multi-validator-svc.multi-validator.svc.cluster.local:${p.port}`,
        auth: {
          kind: 'self-signed',
          user: `validator-user-${i}`,
          audience: 'https://canton.network.global',
          secret: 'test',
        },
      })
    );

    const validators = envFlag('K6_ENABLE_LOAD_GENERATOR_VALIDATORS', false)
      ? [...multiValidatorConfigs, validator1]
      : [validator1];

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
          validators,
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
