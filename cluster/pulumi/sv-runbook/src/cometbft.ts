import * as k8s from '@pulumi/kubernetes';
import * as _ from 'lodash';
import { jsonStringify, Output, Resource } from '@pulumi/pulumi';
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
  svCometBftKeysFromSecret,
  stableCometBftChainId,
  clusterSmallDisk,
  config,
  cometbftRetainBlocks,
  CLUSTER_BASENAME,
} from 'cn-pulumi-common';

type NodeKeyContent = { priv_key: { type: string; value: Output<string> | string } };
type ValidatorKeyContent = {
  address: string;
  pub_key: {
    type: string;
    value: Output<string> | string;
  };
  priv_key: {
    type: string;
    value: Output<string> | string;
  };
};

const svCometBftKeys = svCometBftKeysFromSecret('sv-cometbft-keys');

const nodeKeyContent: NodeKeyContent = {
  priv_key: {
    type: 'tendermint/PrivKeyEd25519',
    value: svCometBftKeys.nodePrivateKey,
  },
};
const validatorKeyContent: ValidatorKeyContent = {
  address: '0647E4FF27908B8B874C2647536AC986C9EA0BAB',
  pub_key: {
    type: 'tendermint/PubKeyEd25519',
    value: svCometBftKeys.validatorPublicKey,
  },
  priv_key: {
    type: 'tendermint/PrivKeyEd25519',
    value: svCometBftKeys.validatorPrivateKey,
  },
};

export function installCometBftNode(
  xns: ExactNamespace,
  svName: string,
  logLevel: string,
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
        'node_key.json': jsonStringify(nodeKeyContent).apply(s =>
          Buffer.from(s).toString('base64')
        ),
        'priv_validator_key.json': jsonStringify(validatorKeyContent).apply(s =>
          Buffer.from(s).toString('base64')
        ),
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
          TARGET_CLUSTER: CLUSTER_BASENAME,
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
          logLevel,
          genesis: {
            // for TestNet-like deployments on scratchnet, set the chainId to 'test'
            chainId:
              `${CLUSTER_BASENAME}`.startsWith('scratch') && !isDevNet
                ? 'test'
                : cometBftValues.genesis.chainId,
            chainIdSuffix: stableCometBftChainId ? cometBftValues.genesis.chainIdSuffix : '',
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
          db: {
            volumeSize: clusterSmallDisk ? '240Gi' : undefined,
          },
          extraLogLevelFlags: config.optionalEnv('COMETBFT_EXTRA_LOG_LEVEL_FLAGS'),
        }),
        version,
        dependencies
      );
    }
  ).activeComponent;
}
