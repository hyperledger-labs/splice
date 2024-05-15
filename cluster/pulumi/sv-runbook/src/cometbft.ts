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
  CLUSTER_HOSTNAME,
  CLUSTER_BASENAME,
  isMainNet,
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
          // We don't currently support deploying the sv runbook on a different
          // cluster from the rest of the network, so TARGET_CLUSTER == YOUR_CLUSTER
          TARGET_CLUSTER: CLUSTER_BASENAME,
          TARGET_HOSTNAME: CLUSTER_HOSTNAME,
          MIGRATION_ID: migrationId.toString(),
          YOUR_SV_NAME: svName,
          YOUR_COMETBFT_NODE_ID: '9116f5faed79dcf98fa79a2a40865ad9b493f463',
          YOUR_HOSTNAME: CLUSTER_HOSTNAME,
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
          // TODO(#12361): Avoid duplicating these config values here by moving them into pulumi/common.
          founder: isMainNet
            ? {
                nodeId: '4c7c99516fb3309b89b7f8ed94690994c8ec0ab0',
                keyAddress: '9473617BBC80C12F68CC25B5A754D1ED9035886C',
                publicKey: 'H2bcJU2zbzbLmP78YWiwMgtB0QG1MNTSozGl1tP11hI=',
              }
            : {
                nodeId: '5af57aa83abcec085c949323ed8538108757be9c',
                publicKey: 'gpkwc1WCttL8ZATBIPWIBRCrb0eV4JwMCnjRa56REPw=',
                keyAddress: '8A931AB5F957B8331BDEF3A0A081BD9F017A777F',
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
              `${CLUSTER_HOSTNAME}`.startsWith('scratch') && !isDevNet
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
            migration: {
              id: migrationId,
              active: isActive,
            },
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
