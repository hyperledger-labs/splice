import * as pulumi from '@pulumi/pulumi';
import { Service } from '@pulumi/kubernetes/core/v1';
import { Output } from '@pulumi/pulumi';
import {
  clusterLargeDisk,
  CLUSTER_DNS_NAME,
  CLUSTER_BASENAME,
  ExactNamespace,
  installCNHelmChart,
  isDevNet,
} from 'cn-pulumi-common';

const sv1NodeConfig = {
  id: '5af57aa83abcec085c949323ed8538108757be9c',
  privateKey:
    '/7L74Bs18740fTPdEL04BeO2Gs+1lzEeCjAiB1DYcysmLnU1FAkg/Ho9XsOiIp4U/KT/YNrtIi/A0prm/Ew3eQ==',
  identifier: 'cometbft-sv-1',
  externalAddress: p2pExternalAddress(26656),
  istioPort: 26656,
  validator: {
    keyAddress: '8A931AB5F957B8331BDEF3A0A081BD9F017A777F',
    privateKey:
      'npgiYbG0Iaslb/JHzliAg5BkfYMOaK3tCdKWvvO4FjCCmTBzVYK20vxkBMEg9YgFEKtvR5XgnAwKeNFrnpEQ/A==',
    publicKey: 'gpkwc1WCttL8ZATBIPWIBRCrb0eV4JwMCnjRa56REPw=',
  },
};

const founder = {
  nodeId: sv1NodeConfig.id,
  publicKey: sv1NodeConfig.validator.publicKey,
  keyAddress: sv1NodeConfig.validator.keyAddress,
  externalAddress: p2pServiceAddress('cometbft-sv-1', 'sv-1'),
};

const nodeConfigs: {
  [key: string]: NodeConfig;
} = {
  'sv-1': sv1NodeConfig,
  'sv-2': {
    id: 'c36b3bbd969d993ba0b4809d1f587a3a341f22c1',
    privateKey:
      '1Je33z2g+Dj2UWLqnsO+xwUwbalIS0LLcYAoj+fYuEE2le4kJjJ0h+L7FfVg+3mbgvrikdke91I2X5C2frj0Eg==',
    identifier: 'cometbft-sv-2',
    externalAddress: p2pExternalAddress(26666),
    istioPort: 26666,
    validator: {
      keyAddress: '04A57312179F1E0C93B868779EE4C7FAC41666F0',
      privateKey:
        '58rlJ+0WYnpfgn26k4TjBAibToi+irz8C2x4XEVBWIgFVIz3+48YtTuUmPvZJTDVrbrXPYvpjLZcouGlS9vGoQ==',
      publicKey: 'BVSM9/uPGLU7lJj72SUw1a261z2L6Yy2XKLhpUvbxqE=',
    },
  },
  'sv-3': {
    id: '0d8e87c54d199e85548ccec123c9d92966ec458c',
    privateKey:
      'DdbW/buPo4TXxW+/cvQxp5Lh1BZyH5GYHGoU0uTUQA/S1oZ32DDu1+CZhtrZhqEFMuxPlXVXvyvXsZLBdCkgdQ==',
    identifier: 'cometbft-sv-3',
    externalAddress: p2pExternalAddress(26676),
    istioPort: 26676,
    validator: {
      keyAddress: 'FFF137F42421B0257CDC8B2E41F777B81A081E80',
      privateKey:
        'y+vvdb5lKRMplJ3mWe5NMdN293Nm6BSzDJFV0txGGKt3GbifUxE/8a5ISQkjBt0HjNVwYB5pyiEUo21soryhEA==',
      publicKey: 'dxm4n1MRP/GuSEkJIwbdB4zVcGAeacohFKNtbKK8oRA=',
    },
  },
  'sv-4': {
    id: 'ee738517c030b42c3ff626d9f80b41dfc4b1a3b8',
    privateKey:
      'xMB8gnYbacyZqU94cgwJBK2OJO3DffO12uHgeieotVj/Q9LbZEwLue9GnG8+G5GNRDgX8z75txr/Z541Uqyb3A==',
    identifier: 'cometbft-sv-4',
    externalAddress: p2pExternalAddress(26686),
    istioPort: 26686,
    validator: {
      keyAddress: 'DE36D23DE022948A11200ABB9EE07F049D17D903',
      privateKey:
        'nUSWALRjErKf+/SVI93LU5venfN/YvsLaVdLYhbYPFPa6Zl1RL3trpVRcwayAon9VtBtqfFZoVTErVCKaGUSOg==',
      publicKey: '2umZdUS97a6VUXMGsgKJ/VbQbanxWaFUxK1QimhlEjo=',
    },
  },
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
  nodename: string,
  onboardingName: string
): Service {
  const nodeConfig = nodeConfigs[nodename];
  const stateSync = {
    enable: false,
    rpcServers: '',
  };
  // Enabling state sync only for sv-4 during testing
  if (nodename === 'sv-4') {
    stateSync.enable = false;
    stateSync.rpcServers =
      rpcServiceAddress('cometbft-sv-1', 'sv-1') + ',' + rpcServiceAddress('cometbft-sv-1', 'sv-1');
  }
  const cometbftRelease = installCNHelmChart(xns, 'cometbft', 'cn-cometbft', {
    nodeName: onboardingName,
    imageName: 'cometbft',
    founder: founder,
    istioVirtualService: {
      enabled: true,
      gateway: 'cluster-ingress/cn-apps-gateway',
      port: nodeConfig.istioPort,
    },
    node: nodeConfig,
    peers: Object.keys(nodeConfigs)
      .filter(key => key !== nodename && key !== 'sv-1')
      .map(svName => {
        /*
         * We configure the peers explicitly here so that every cometbft node knows about the other nodes.
         * This is required to bypass the use of externalAddress when communicating between cometbft nodes for sv1-sv4
         * We bypass the external address and use the internal kubernetes services address so that there is no requirement for
         * sending the traffic through the loopback to satisfy the firewall rules
         * */
        return {
          nodeId: nodeConfigs[svName].id,
          externalAddress: p2pServiceAddress(`cometbft-${svName}`, svName),
        };
      }),
    isDevNet: isDevNet,
    stateSync: stateSync,
    genesis: {
      chainId: `${CLUSTER_BASENAME}`,
    },
    metrics: {
      enable: true,
    },
    db: {
      volumeSize: clusterLargeDisk ? '320Gi' : '80Gi',
    },
  });
  return Service.get(
    `${nodeConfig.identifier}-cometbft-rpc`,
    pulumi.interpolate`${cometbftRelease.status.namespace}/${nodeConfig.identifier}-cometbft-rpc`
  );
}

type NodeConfig = {
  privateKey: Output<string> | string;
  validator: {
    keyAddress: Output<string> | string;
    privateKey: Output<string> | string;
    publicKey: Output<string> | string;
  };
  istioPort: number;
  externalAddress: string;
  identifier: string;
  id: Output<string> | string;
};

function p2pExternalAddress(port: number): string {
  return `${CLUSTER_DNS_NAME}:${port}`;
}

function p2pServiceAddress(nodename: string, namespace: string): string {
  return `${nodename}-cometbft-p2p.${namespace}.svc.cluster.local:26656`;
}

function rpcServiceAddress(nodename: string, namespace: string): string {
  return `http://${nodename}-cometbft-rpc.${namespace}.svc.cluster.local:26657`;
}
