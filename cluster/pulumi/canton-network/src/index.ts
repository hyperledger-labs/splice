import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0ClusterConfig,
  Auth0Fetch,
  config,
  exactNamespace,
  infraStack,
  isMainNet,
} from 'cn-pulumi-common';
import exec from 'node:child_process';

import { installCluster } from './installCluster';
import { scheduleLoadGenerator } from './scheduleLoadGenerator';

function installClusterVersion(): k8s.apiextensions.CustomResource {
  const ns = exactNamespace('cluster-version', true);
  const host = config.requireEnv('GCP_CLUSTER_HOSTNAME');
  const version = exec
    .execSync(`${config.requireEnv('REPO_ROOT')}/build-tools/get-snapshot-version`)
    .toString();
  return new k8s.apiextensions.CustomResource(
    `cluster-version-virtual-service`,
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'VirtualService',
      metadata: {
        name: 'cluster-version',
        namespace: ns.ns.metadata.name,
      },
      spec: {
        hosts: [host],
        gateways: ['cluster-ingress/cn-http-gateway'],
        http: [
          {
            match: [
              {
                port: 443,
                uri: { exact: '/version' },
              },
            ],
            directResponse: {
              status: 200,
              body: { string: version },
            },
          },
        ],
      },
    },
    { deleteBeforeReplace: true }
  );
}

async function auth0CacheAndInstallCluster(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  installClusterVersion();

  const cluster = await installCluster(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();

  return cluster;
}

async function main() {
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  let auth0FetchOutput: pulumi.Output<Auth0Fetch>;
  if (isMainNet) {
    if (!auth0ClusterCfg.mainnet) {
      throw new Error('missing mainNet auth0 output');
    }
    auth0FetchOutput = auth0ClusterCfg.mainnet.apply(cfg => {
      if (!cfg) {
        throw new Error('missing mainNet auth0 output');
      }
      cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET');
      return new Auth0Fetch(cfg);
    });
  } else {
    if (!auth0ClusterCfg.cantonNetwork) {
      throw new Error('missing cantonNetwork auth0 output');
    }
    auth0FetchOutput = auth0ClusterCfg.cantonNetwork.apply(cfg => {
      if (!cfg) {
        throw new Error('missing cantonNetwork auth0 output');
      }
      cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');
      return new Auth0Fetch(cfg);
    });
  }

  auth0FetchOutput.apply(async auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    const cluster = await auth0CacheAndInstallCluster(auth0Fetch);

    scheduleLoadGenerator(auth0Fetch, cluster.validator1 ? [cluster.validator1] : []);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
