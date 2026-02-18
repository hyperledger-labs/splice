// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  activeVersion,
  Auth0Client,
  CLUSTER_HOSTNAME,
  config,
  exactNamespace,
  generatePortSequence,
  imagePullSecret,
  installSpliceHelmChart,
  isDevNet,
  numInstances,
  numNodesPerInstance,
  loadTesterConfig,
  CnChartVersion,
  getNamespaceConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { installLoopback } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { Resource } from '@pulumi/pulumi';

export function scheduleLoadGenerator(auth0Client: Auth0Client, dependencies: Resource[]): void {
  if (loadTesterConfig?.enable) {
    const xns = exactNamespace('load-tester', true);

    const imagePullDeps = imagePullSecret(xns);

    const clusterHostname = `${CLUSTER_HOSTNAME}`;

    const loopback = installLoopback(xns);

    const oauthDomain = `https://${auth0Client.getCfg().auth0Domain}`;
    const oauthClientId = getNamespaceConfig(auth0Client.getCfg(), 'validator1').uiClientIds.wallet;
    const audience = config.requireEnv('OIDC_AUTHORITY_VALIDATOR_AUDIENCE'); // TODO(#2873): Is there a good reason we don't read this from the same config?
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
        audience,
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
          audience,
          secret: 'test',
        },
      }))
    );

    const validators = numInstances > 0 ? multiValidatorConfigs : [validator1];

    installSpliceHelmChart(
      xns,
      'load-tester',
      'splice-load-tester',
      {
        prometheusRw,
        config: JSON.stringify({
          isDevNet,
          usersPerValidator: 10,
          validators,
          test: {
            duration: `365d`,
            iterationsPerMinute: loadTesterConfig.iterationsPerMinute,
            maxVUs: loadTesterConfig.maxVUs,
          },
          adaptiveScenario: loadTesterConfig.adaptiveScenario,
        }),
        ...(loadTesterConfig.resources ? { resources: loadTesterConfig.resources } : {}),
      },
      loadTesterConfig.chartVersion
        ? CnChartVersion.parse(loadTesterConfig.chartVersion)
        : activeVersion,
      { dependsOn: imagePullDeps.concat(dependencies).concat(loopback) }
    );
  } else {
    console.log('K6 load test is disabled for this cluster. Skipping...');
  }
}
