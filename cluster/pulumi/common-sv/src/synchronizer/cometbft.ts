import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as _ from 'lodash';
import { Resource } from '@pulumi/pulumi';
import {
  activeVersion,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  clusterSmallDisk,
  config,
  DomainMigrationIndex,
  ExactNamespace,
  GCP_ZONE,
  InstalledHelmChart,
  installSpliceHelmChart,
  isDevNet,
  loadYamlFromFile,
  REPO_ROOT,
  SpliceCustomResourceOptions,
  withAddedDependencies,
} from 'splice-pulumi-common';
import { CnChartVersion } from 'splice-pulumi-common/src/artifacts';

import { svsConfiguration } from '../clusterSvConfig';
import { svConfig } from '../config';
import { CometBftNodeConfigs } from './cometBftNodeConfigs';
import { disableCometBftStateSync } from './cometbftConfig';

export type Cometbft = {
  rpcServiceName: string;
  release: InstalledHelmChart;
};

// TODO(#16510) -- retrieve exact chain id directly from an env var / external config
const getChainId = (migrationId: number): string => {
  if (`${CLUSTER_BASENAME}`.startsWith('scratch') && !isDevNet) {
    return 'test';
  }

  if (CLUSTER_BASENAME === 'testzrh') {
    return `test-${migrationId}`;
  }

  if (CLUSTER_BASENAME === 'mainzrh') {
    return `main-${migrationId}`;
  }

  return `${CLUSTER_BASENAME}-${migrationId}`;
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
  enableTimeoutCommit: boolean = false,
  imagePullServiceAccountName?: string,
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
  const nodeConfig = nodeConfigs.self;
  const isSv1 = nodeConfigs.self.id === nodeConfigs.sv1NodeConfig.id;
  // legacy domains don't need cometbft state sync because no new nodes will join
  // upgrade domains don't need cometbft state sync because until they are active cometbft will not really progress its height a lot
  // also for upgrade domains we first deploy the domain and then redeploy the sv app, and as we proxy the calls for state sync through the
  // sv-app we cannot configure state sync until the sv app has migrated
  // if a migration is running we must not configure state sync because that will also add a pulumi dependency and our migrate flow will break (sv2-4 depending on sv1)
  const stateSyncEnabled = !isSv1 && enableStateSync && !isRunningMigration && isActiveDomain;
  const cometbftChartValues = _.mergeWith(cometBftValues, {
    sv1: nodeConfigs.sv1,
    // TODO (#13845) remove when ciperiodic version >= 0.1.18
    founder: nodeConfigs.sv1,
    istioVirtualService: {
      enabled: true,
      gateway: 'cluster-ingress/cn-apps-gateway',
      port: nodeConfig.istioPort,
    },
    node: {
      ...cometBftValues.node,
      ...nodeConfig,
      ...(nodeConfig.validator ? { keysSecret: '' } : {}),
      enableTimeoutCommit,
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
      chainId: getChainId(migrationId),
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
      volumeSize: clusterSmallDisk ? '240Gi' : svConfig?.cometbft?.volumeSize,
    },
    extraLogLevelFlags: config.optionalEnv('COMETBFT_EXTRA_LOG_LEVEL_FLAGS'),
    serviceAccountName: imagePullServiceAccountName,
  });
  const svIdentifier = nodeConfigs.selfSvNodeName;
  const svIdentifierWithMigration = `${svIdentifier}-m${migrationId}`;
  const svConfiguration = svsConfiguration[svIdentifier];
  let volumeDependecies: Resource[] = [];
  if (svConfiguration?.cometbft) {
    const volumeSize = cometbftChartValues.db.volumeSize;
    const diskSnapshot = gcp.compute.getSnapshot({
      name: svConfiguration.cometbft.snapshotName,
    });

    if (!GCP_ZONE) {
      throw new Error('Zone is required to create a disk');
    }
    const restoredDisk = new gcp.compute.Disk(
      `${svIdentifierWithMigration}-cometbft-restored-data`,
      {
        name: `${svIdentifierWithMigration}-cometbft-restored-disk`,
        // eslint-disable-next-line promise/prefer-await-to-then
        size: diskSnapshot.then(snapshot => snapshot.diskSizeGb),
        // eslint-disable-next-line promise/prefer-await-to-then
        snapshot: diskSnapshot.then(snapshot => snapshot.selfLink),
        type: 'pd-ssd',
        zone: GCP_ZONE,
      },
      opts
    );

    // create the underlying persistent volume that will be used by cometbft from the state of an existing PV
    volumeDependecies = [
      new k8s.core.v1.PersistentVolume(
        `${svIdentifier}-cometbft-data`,
        {
          metadata: {
            name: `${svIdentifier}-cometbft-data-pv`,
          },
          spec: {
            capacity: {
              storage: volumeSize,
            },
            volumeMode: 'Filesystem',
            accessModes: ['ReadWriteOnce'],
            persistentVolumeReclaimPolicy: 'Delete',
            storageClassName: cometbftChartValues.db.volumeStorageClass,
            claimRef: {
              name: `global-domain-${migrationId}-cometbft-cometbft-data`,
              namespace: xns.ns.metadata.name,
            },
            csi: {
              driver: 'pd.csi.storage.gke.io',
              volumeHandle: restoredDisk.id,
            },
          },
        },
        opts
      ),
    ];
  }
  const release = installSpliceHelmChart(
    xns,
    `cometbft-global-domain-${migrationId}`,
    `splice-cometbft`,
    cometbftChartValues,
    version,
    // support old runbook names, can be removed once the runbooks are all reset and latest release is >= 0.2.x
    {
      ...withAddedDependencies(opts, volumeDependecies),
      aliases: [{ name: `global-domain-${migrationId}-cometbft`, parent: undefined }],
      ignoreChanges: ['name'],
    }
  );
  return { rpcServiceName: `${nodeConfig.identifier}-cometbft-rpc`, release };
}
