import * as k8s from '@pulumi/kubernetes';
import * as fs from 'fs';
import * as _ from 'lodash';
import { Resource } from '@pulumi/pulumi';
import {
  ExactNamespace,
  isDevNet,
  installCNRunbookHelmChart,
  installMigrationIdSpecificComponent,
  loadYamlFromFile,
  REPO_ROOT,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  disableCometBftStateSync,
} from 'cn-pulumi-common';
import { cometbftRetainBlocks } from 'cn-pulumi-common/src/deployment_config';

import { CLUSTER_BASENAME, TARGET_CLUSTER } from './utils';

const nodeKeyContent = fs.readFileSync(
  `${REPO_ROOT}/cluster/pulumi/sv-runbook/cometbft/node_key.json`,
  'utf-8'
);
const privValidatorKeyContent = fs.readFileSync(
  `${REPO_ROOT}/cluster/pulumi/sv-runbook/cometbft/priv_validator_key.json`,
  'utf-8'
);

export function installCometBftNode(
  xns: ExactNamespace,
  svName: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
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
  return installMigrationIdSpecificComponent(
    decentralizedSynchronizerMigrationConfig,
    (migrationId, isActive, version) => {
      const cometBftValues = loadYamlFromFile(
        `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/cometbft-values.yaml`,
        {
          TARGET_CLUSTER: TARGET_CLUSTER,
          MIGRATION_ID: migrationId.toString(),
          YOUR_SV_NAME: svName,
          YOUR_COMETBFT_NODE_ID: '9116f5faed79dcf98fa79a2a40865ad9b493f463',
          YOUR_HOSTNAME: `${CLUSTER_BASENAME}.network.canton.global`,
        }
      );
      return installCNRunbookHelmChart(
        xns,
        `global-domain-${migrationId}-cometbft`,
        'cn-cometbft',
        _.mergeWith(cometBftValues, {
          node: {
            retainBlocks: cometbftRetainBlocks,
          },
          istioVirtualService: {
            enabled: true,
            gateway: 'cluster-ingress/cn-apps-gateway',
            port: `26${migrationId}56`,
          },
          genesis: {
            // for TestNet-like deployments on scratchnet, set the chainId to 'test'
            chainId:
              `${CLUSTER_BASENAME}`.startsWith('scratch') && !isDevNet
                ? 'test'
                : cometBftValues.genesis.chainId,
            // TODO(#11434) Clean this up
            chainIdVersion: '0.1.1-snapshot.20240328.5498.0.vf695ebd5',
          },
          stateSync: {
            ...cometBftValues.stateSync,
            enable:
              !disableCometBftStateSync &&
              !decentralizedSynchronizerMigrationConfig.isRunningMigration() &&
              isActive
                ? cometBftValues.stateSync.enable
                : false,
          },
          metrics: {
            enable: true,
            labels: [{ key: 'active_migration', value: isActive }],
          },
        }),
        version,
        dependencies
      );
    }
  ).activeComponent;
}
