import * as k8s from '@pulumi/kubernetes';
import * as fs from 'fs';
import * as _ from 'lodash';
import { Resource } from '@pulumi/pulumi';
import {
  ExactNamespace,
  isDevNet,
  cometbftRetainBlocks,
  installCNRunbookHelmChart,
  loadYamlFromFile,
  REPO_ROOT,
  CnInput,
} from 'cn-pulumi-common';

import { CLUSTER_BASENAME, localCharts, TARGET_CLUSTER, version } from './utils';

const nodeKeyContent = fs.readFileSync(
  `${REPO_ROOT}/cluster/pulumi/sv-runbook/cometbft/node_key.json`,
  'utf-8'
);
const privValidatorKeyContent = fs.readFileSync(
  `${REPO_ROOT}/cluster/pulumi/sv-runbook/cometbft/priv_validator_key.json`,
  'utf-8'
);

const domainActiveMigrationId = process.env.GLOBAL_DOMAIN_ACTIVE_MIGRATION_ID || '0';

export function installCometBftNode(
  xns: ExactNamespace,
  svName: string,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
  const cometBftValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/cometbft-values.yaml`,
    {
      TARGET_CLUSTER: TARGET_CLUSTER,
      YOUR_SV_NAME: svName,
      YOUR_COMETBFT_NODE_ID: '9116f5faed79dcf98fa79a2a40865ad9b493f463',
      YOUR_HOSTNAME: `${CLUSTER_BASENAME}.network.canton.global`,
    }
  );

  new k8s.core.v1.Secret(
    'cometbft-keys',
    {
      metadata: {
        name: 'cometbft-keys',
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        'node_key.json': Buffer.from(nodeKeyContent).toString('base64'),
        'priv_validator_key.json': Buffer.from(privValidatorKeyContent).toString('base64'),
      },
    },
    { dependsOn: dependencies.concat([xns.ns]) }
  );
  const includeMigrationIdInChainId = domainActiveMigrationId !== '0';
  return installCNRunbookHelmChart(
    xns,
    'cometbft',
    'cn-cometbft',
    _.mergeWith(cometBftValues, {
      node: {
        externalAddress: `cometbft.svc.${CLUSTER_BASENAME}.network.canton.global:26096`,
        retainBlocks: cometbftRetainBlocks,
      },
      istioVirtualService: {
        enabled: true,
        gateway: 'cluster-ingress/cn-apps-gateway',
        port: 26096,
      },
      stateSync: {
        rpcServers: rpcServiceAddress('sv-1') + ',' + rpcServiceAddress('sv-1'),
      },
      genesis: {
        // for TestNet-like deployments on scratchnet, set the chainId to 'test'
        chainId:
          `${CLUSTER_BASENAME}`.startsWith('scratch') && !isDevNet
            ? 'test'
            : `${CLUSTER_BASENAME}` +
              (includeMigrationIdInChainId ? `-${domainActiveMigrationId}` : ''),
      },
      founder: {
        externalAddress: `${CLUSTER_BASENAME}.network.canton.global:26${domainActiveMigrationId}16`,
      },
    }),
    localCharts,
    version,
    dependencies
  );
}

function rpcServiceAddress(namespace: string): string {
  // Note that the port number is significant in the rpcServer address used for state sync
  return `https://sv.${namespace}.svc.${CLUSTER_BASENAME}.network.canton.global:443/api/sv/v0/admin/domain/cometbft/json-rpc`;
}
