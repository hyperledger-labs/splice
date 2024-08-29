import { Resource } from '@pulumi/pulumi';
import {
  Auth0Client,
  CLUSTER_HOSTNAME,
  defaultVersion,
  config,
  exactNamespace,
  generatePortSequence,
  installSpliceHelmChart,
  isDevNet,
  numInstances,
  numNodesPerInstance,
} from 'cn-pulumi-common';

export function scheduleLoadGenerator(auth0Client: Auth0Client, dependencies: Resource[]): void {
  if (config.envFlag('K6_ENABLE_LOAD_GENERATOR')) {
    const xns = exactNamespace('load-tester', true);

    const clusterHostname = `${CLUSTER_HOSTNAME}`;

    // install loopback so the test can hit the wallet/validator API via its public DNS name
    const loopback = installSpliceHelmChart(
      xns,
      'loopback',
      'cn-cluster-loopback-gateway',
      {
        cluster: {
          hostname: CLUSTER_HOSTNAME,
        },
      },
      defaultVersion,
      { dependsOn: [xns.ns] }
    );

    const oauthDomain = `https://${auth0Client.getCfg().auth0Domain}`;
    const oauthClientId = auth0Client.getCfg().namespaceToUiToClientId?.validator1?.wallet;
    const usersPassword = config.requireEnv('K6_USERS_PASSWORD');

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
          clientId: config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_ID'),
          clientSecret: config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET'),
        },
        admin: {
          email: config.optionalEnv('K6_VALIDATOR_ADMIN_USERNAME') || 'admin@validator1.com',
          password: config.requireEnv('K6_VALIDATOR_ADMIN_PASSWORD'),
        },
      },
    };

    const multiValidatorConfigs = new Array(numInstances).fill(0).flatMap((_, instance) =>
      generatePortSequence(5000, numNodesPerInstance, [{ id: 3 }]).map((p, validator) => ({
        walletBaseUrl: `http://multi-validator-${instance}.multi-validator.svc.cluster.local:${p.port}`,
        auth: {
          kind: 'self-signed',
          user: `validator-user-${validator}`,
          audience: 'https://canton.network.global',
          secret: 'test',
        },
      }))
    );

    const validators = numInstances > 0 ? multiValidatorConfigs : [validator1];

    installSpliceHelmChart(
      xns,
      'load-tester',
      'cn-load-tester',
      {
        prometheusRw,
        config: JSON.stringify({
          isDevNet,
          usersPerValidator: 10,
          validators,
          test: {
            duration: `365d`,
            iterationsPerMinute: 60,
          },
        }),
      },
      defaultVersion,
      { dependsOn: dependencies.concat([loopback]) }
    );
  } else {
    console.log('K6 load test is disabled for this cluster. Skipping...');
  }
}
