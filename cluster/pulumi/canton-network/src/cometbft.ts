import * as pulumi from '@pulumi/pulumi';
import { Service } from '@pulumi/kubernetes/core/v1';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { CustomResourceOptions } from '@pulumi/pulumi';
import {
  CLUSTER_BASENAME,
  CLUSTER_DNS_NAME,
  clusterLargeDisk,
  ExactNamespace,
  installCNHelmChart,
  isDevNet,
  DefaultMigrationId,
  DomainMigrationIndex,
} from 'cn-pulumi-common';

import { StaticCometBftConfig, StaticCometBftConfigWithNodeName } from './svconfs';

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
  nodename: string,
  onboardingName: string,
  nodeConfigs: {
    self: StaticCometBftConfigWithNodeName;
    founder: StaticCometBftConfigWithNodeName;
    peers: StaticCometBftConfigWithNodeName[];
  },
  migrationId: DomainMigrationIndex,
  syncSource?: Release,
  opts?: CustomResourceOptions
): Service {
  const configs = new CometBftNodeConfig(migrationId, nodeConfigs);
  const nodeConfig = configs.nodeConfigs[nodename];
  let stateSyncConfig;
  if (syncSource) {
    const rpcServer = syncSource.status.namespace.apply(namespace =>
      rpcServiceAddress(namespace, migrationId)
    );
    stateSyncConfig = {
      enable: true,
      rpcServers: pulumi.interpolate`${rpcServer},${rpcServer}`,
    };
  } else {
    stateSyncConfig = { enable: false };
  }
  // for backwards compatibility, we keep the old chainId for the default global domain
  const includeMigrationIdInChainId = migrationId !== DefaultMigrationId;
  const cometbftRelease = installCNHelmChart(
    xns,
    `cometbft-global-domain-${migrationId}`,
    'cn-cometbft',
    {
      nodeName: onboardingName,
      imageName: 'cometbft',
      founder: configs.founder,
      istioVirtualService: {
        enabled: true,
        gateway: 'cluster-ingress/cn-apps-gateway',
        port: nodeConfig.istioPort,
      },
      node: nodeConfig,
      peers: Object.keys(configs.nodeConfigs)
        .filter(key => key !== nodename && key !== 'sv-1')
        .map(svName => {
          /*
           * We configure the peers explicitly here so that every cometbft node knows about the other nodes.
           * This is required to bypass the use of externalAddress when communicating between cometbft nodes for sv1-sv4
           * We bypass the external address and use the internal kubernetes services address so that there is no requirement for
           * sending the traffic through the loopback to satisfy the firewall rules
           * */
          return {
            nodeId: configs.nodeConfigs[svName].id,
            externalAddress: configs.p2pServiceAddress(svName),
          };
        }),
      stateSync: stateSyncConfig,
      genesis: {
        // for TestNet-like deployments on scratchnet, set the chainId to 'test'
        chainId:
          `${CLUSTER_BASENAME}`.startsWith('scratch') && !isDevNet
            ? 'test'
            : `${CLUSTER_BASENAME}` + (includeMigrationIdInChainId ? `-${migrationId}` : ''),
      },
      metrics: {
        enable: true,
      },
      db: {
        volumeSize: clusterLargeDisk ? '480Gi' : '240Gi',
      },
    },
    {
      ...opts,
      ...{ dependsOn: syncSource ? [syncSource] : [] },
    }
  );
  return Service.get(
    `${nodename}-${migrationId}-cometbft-rpc`,
    pulumi.interpolate`${cometbftRelease.status.namespace}/${nodeConfig.identifier}-cometbft-rpc`
  );
}

interface NodeConfig extends Omit<StaticCometBftConfig, 'nodeIndex'> {
  istioPort: number;
  externalAddress: string;
  identifier: string;
}

function rpcServiceAddress(namespace: string, migrationId: DomainMigrationIndex): string {
  return `http://sv-app-${migrationId}.${namespace}.svc.cluster.local:5014/api/sv/v0/admin/domain/cometbft/json-rpc`;
}

class CometBftNodeConfig {
  private readonly _domainMigrationId: number;
  private readonly _nodeConfigs: {
    self: StaticCometBftConfigWithNodeName;
    founder: StaticCometBftConfigWithNodeName;
    peers: StaticCometBftConfigWithNodeName[];
  };

  constructor(
    domainMigrationId: number,
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      founder: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    }
  ) {
    this._domainMigrationId = domainMigrationId;
    this._nodeConfigs = nodeConfigs;
  }

  private staticToNodeConfig(staticConf: StaticCometBftConfig): NodeConfig {
    return {
      id: staticConf.id,
      privateKey: staticConf.privateKey,
      identifier: this.nodeIdentifier,
      externalAddress: this.p2pExternalAddress(staticConf.nodeIndex),
      istioPort: this.istioExternalPort(staticConf.nodeIndex),
      retainBlocks: staticConf.retainBlocks,
      validator: {
        keyAddress: staticConf.validator.keyAddress,
        privateKey: staticConf.validator.privateKey,
        publicKey: staticConf.validator.publicKey,
      },
    };
  }

  get sv1NodeConfig() {
    return this.staticToNodeConfig(this._nodeConfigs.founder);
  }

  p2pServiceAddress(nodename: string): string {
    return `${this.nodeIdentifier}-cometbft-p2p.${nodename}.svc.cluster.local:26656`;
  }

  get nodeIdentifier() {
    return `global-domain-${this._domainMigrationId}-cometbft`;
  }

  get founder() {
    return {
      nodeId: this.sv1NodeConfig.id,
      publicKey: this.sv1NodeConfig.validator.publicKey,
      keyAddress: this.sv1NodeConfig.validator.keyAddress,
      externalAddress: this.p2pServiceAddress(this._nodeConfigs.founder.nodeName),
    };
  }

  get nodeConfigs(): {
    [key: string]: NodeConfig;
  } {
    return [this._nodeConfigs.founder, this._nodeConfigs.self, ...this._nodeConfigs.peers].reduce<{
      [key: string]: NodeConfig;
    }>((acc, staticConf) => {
      return { ...acc, [staticConf.nodeName]: this.staticToNodeConfig(staticConf) };
    }, {});
  }

  private p2pExternalAddress(nodeIndex: DomainMigrationIndex): string {
    return `${CLUSTER_DNS_NAME}:${this.istioExternalPort(nodeIndex)}`;
  }

  private istioExternalPort(nodeIndex: DomainMigrationIndex) {
    return Number(`26${this._domainMigrationId}${nodeIndex}6`);
  }
}
