import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';
import {
  activeVersion,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  clusterSmallDisk,
  config,
  DomainMigrationIndex,
  ExactNamespace,
  installSpliceHelmChart,
  isDevNet,
  loadYamlFromFile,
  REPO_ROOT,
  SpliceCustomResourceOptions,
} from 'splice-pulumi-common';
import { CnChartVersion } from 'splice-pulumi-common/src/artifacts';

import { CometBftNodeConfig, CometBftNodeConfigs } from './cometBftNodeConfigs';
import { disableCometBftStateSync } from './cometbftConfig';

export type Cometbft = {
  rpcServiceName: string;
  release: Release;
};

/**
 * The CometBft deployment uses a different port for the istio VirtualService for each node
 * Then all the ports must be added to the gateway so that we can forward the traffic as expected.
 * This is done because CometBft does not actually support adding multiple nodes with the same ip:port configuration.
 * It seems that CometBft stores the address of known peers by actually storing the IP:Port combination and discarding the used dns,
 * therefore having only different DNS entries that point to a different service is not enough.
 * Furthermore, even if we register multiple istio VirtualServices with different hosts, but for the same port in the gateway,
 * istio will just ignore the host criteria for TCP ports.
 * */
export function installCometBftNode(
  xns: ExactNamespace,
  onboardingName: string,
  nodeConfigs: CometBftNodeConfigs,
  migrationId: DomainMigrationIndex,
  isActiveDomain: boolean,
  isRunningMigration: boolean,
  logLevel: string,
  version: CnChartVersion = activeVersion,
  enableStateSync: boolean = !disableCometBftStateSync,
  opts?: SpliceCustomResourceOptions
): Cometbft {
  const cometBftValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/cometbft-values.yaml`,
    {
      TARGET_CLUSTER: CLUSTER_BASENAME,
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      MIGRATION_ID: migrationId.toString(),
      YOUR_SV_NAME: onboardingName,
      YOUR_COMETBFT_NODE_ID: nodeConfigs.self.id,
      YOUR_HOSTNAME: CLUSTER_HOSTNAME,
    }
  );
  const nodeConfig: CometBftNodeConfig = nodeConfigs.self;
  const enableTimeoutCommitSv1 = config.envFlag('COMETBFT_ENABLE_TIMEOUT_COMMIT_SV1', false);
  const isSv1 = nodeConfigs.self.id === nodeConfigs.sv1NodeConfig.id;
  // legacy domains don't need cometbft state sync because no new nodes will join
  // upgrade domains don't need cometbft state sync because until they are active cometbft will not really progress its height a lot
  // also for upgrade domains we first deploy the domain and then redeploy the sv app, and as we proxy the calls for state sync through the
  // sv-app we cannot configure state sync until the sv app has migrated
  // if a migration is running we must not configure state sync because that will also add a pulumi dependency and our migrate flow will break (sv2-4 depending on sv1)
  const stateSyncEnabled = !isSv1 && enableStateSync && !isRunningMigration && isActiveDomain;
  const release = installSpliceHelmChart(
    xns,
    `cometbft-global-domain-${migrationId}`,
    'splice-cometbft',
    _.mergeWith(cometBftValues, {
      sv1: nodeConfigs.sv1,
      // TODO (#13845) remove when ciperiodic version >= 0.1.18
      founder: nodeConfigs.sv1,
      istioVirtualService: {
        enabled: true,
        gateway: 'cluster-ingress/splice-apps-gateway',
        port: nodeConfig.istioPort,
      },
      node: {
        ...cometBftValues.node,
        ...nodeConfig,
        ...(nodeConfig.validator ? { keysSecret: '' } : {}),
        enableTimeoutCommit: isSv1 && enableTimeoutCommitSv1,
      },
      logLevel,
      peers: nodeConfigs.peers
        .filter(peer => peer.id !== nodeConfigs.self.id && peer.id !== nodeConfigs.sv1.nodeId)
        .map(peer => {
          /*
           * We configure the peers explicitly here so that every cometbft node knows about the other nodes.
           * This is required to bypass the use of externalAddress when communicating between cometbft nodes for sv1-sv4
           * We bypass the external address and use the internal kubernetes services address so that there is no requirement for
           * sending the traffic through the loopback to satisfy the firewall rules
           * */
          return {
            nodeId: peer.id,
            externalAddress: nodeConfigs.p2pServiceAddress(peer.id),
          };
        }),
      stateSync: {
        ...cometBftValues.stateSync,
        enable: stateSyncEnabled,
      },
      genesis: {
        // for TestNet-like deployments on scratchnet, set the chainId to 'test'
        chainId:
          `${CLUSTER_BASENAME}`.startsWith('scratch') && !isDevNet
            ? 'test'
            : `${CLUSTER_BASENAME}-${migrationId}`,
        chainIdSuffix: config.optionalEnv('COMETBFT_CHAIN_ID_SUFFIX') || '0',
      },
      metrics: {
        enable: true,
        migration: {
          id: migrationId,
          active: isActiveDomain,
        },
        labels: [{ key: 'active_migration', value: isActiveDomain }],
      },
      db: {
        volumeSize: clusterSmallDisk ? '240Gi' : undefined,
      },
      extraLogLevelFlags: config.optionalEnv('COMETBFT_EXTRA_LOG_LEVEL_FLAGS'),
    }),
    version,
    // support old runbook names, can be removed once the runbooks are all reset and latest release is >= 0.2.x
    {
      ...opts,
      aliases: [{ name: `global-domain-${migrationId}-cometbft`, parent: undefined }],
      ignoreChanges: ['name'],
    }
  );
  return { rpcServiceName: `${nodeConfig.identifier}-cometbft-rpc`, release };
}
