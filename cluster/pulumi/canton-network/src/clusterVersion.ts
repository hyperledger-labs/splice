import * as k8s from '@pulumi/kubernetes';
import exec from 'node:child_process';
import { config, defaultVersion, exactNamespace } from 'splice-pulumi-common';

export function installClusterVersion(): k8s.apiextensions.CustomResource {
  const ns = exactNamespace('cluster-version', true);
  const host = config.requireEnv('GCP_CLUSTER_HOSTNAME');
  const remoteVersion = defaultVersion.type == 'remote' ? defaultVersion.version : undefined;
  const version =
    remoteVersion ||
    // cannot be used with the operator
    exec.execSync(`${config.requireEnv('REPO_ROOT')}/build-tools/get-snapshot-version`).toString();
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
