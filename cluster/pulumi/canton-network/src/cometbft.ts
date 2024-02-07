import * as pulumi from '@pulumi/pulumi';
import { Service } from '@pulumi/kubernetes/core/v1';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { CustomResourceOptions, Output } from '@pulumi/pulumi';
import {
  CLUSTER_BASENAME,
  CLUSTER_DNS_NAME,
  clusterLargeDisk,
  envFlag,
  ExactNamespace,
  installCNHelmChart,
  isDevNet,
} from 'cn-pulumi-common';

import { DefaultGlobalDomainId, DomainIndex } from './globalDomainNode';

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
  domain: DomainIndex,
  syncSource?: Release,
  opts?: CustomResourceOptions
): Service {
  const configs = new CometBftNodeConfig(domain);
  const nodeConfig = configs.nodeConfigs[nodename];
  let stateSyncConfig;
  if (syncSource) {
    const rpcServer = syncSource.status.namespace.apply(namespace =>
      rpcServiceAddress(namespace, domain)
    );
    stateSyncConfig = {
      enable: true,
      rpcServers: pulumi.interpolate`${rpcServer},${rpcServer}`,
    };
  } else {
    stateSyncConfig = { enable: false };
  }
  // for backwards compatibility, we keep the old chainId for the default global domain
  const includeDomainInChainId = domain !== DefaultGlobalDomainId;
  const cometbftRelease = installCNHelmChart(
    xns,
    `cometbft-global-domain-${domain}`,
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
            : `${CLUSTER_BASENAME}` + (includeDomainInChainId ? `-${domain}` : ''),
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
    `${nodename}-${domain}-cometbft-rpc`,
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
  retainBlocks?: number;
  id: Output<string> | string;
};

function rpcServiceAddress(namespace: string, domain: DomainIndex): string {
  return `http://sv-app-${domain}.${namespace}.svc.cluster.local:5014/api/sv/v0/admin/domain/cometbft/json-rpc`;
}

class CometBftNodeConfig {
  private readonly _domain: number;

  constructor(domain: number) {
    this._domain = domain;
  }

  get sv1NodeConfig() {
    return {
      id: '5af57aa83abcec085c949323ed8538108757be9c',
      privateKey:
        '/7L74Bs18740fTPdEL04BeO2Gs+1lzEeCjAiB1DYcysmLnU1FAkg/Ho9XsOiIp4U/KT/YNrtIi/A0prm/Ew3eQ==',
      identifier: this.nodeIdentifier,
      externalAddress: this.p2pExternalAddress(1),
      istioPort: this.istioExternalPort(1),
      validator: {
        keyAddress: '8A931AB5F957B8331BDEF3A0A081BD9F017A777F',
        privateKey:
          'npgiYbG0Iaslb/JHzliAg5BkfYMOaK3tCdKWvvO4FjCCmTBzVYK20vxkBMEg9YgFEKtvR5XgnAwKeNFrnpEQ/A==',
        publicKey: 'gpkwc1WCttL8ZATBIPWIBRCrb0eV4JwMCnjRa56REPw=',
      },
    };
  }

  p2pServiceAddress(nodename: string): string {
    return `${this.nodeIdentifier}-cometbft-p2p.${nodename}.svc.cluster.local:26656`;
  }

  get nodeIdentifier() {
    return `global-domain-${this._domain}-cometbft`;
  }

  get founder() {
    return {
      nodeId: this.sv1NodeConfig.id,
      publicKey: this.sv1NodeConfig.validator.publicKey,
      keyAddress: this.sv1NodeConfig.validator.keyAddress,
      externalAddress: this.p2pServiceAddress('sv-1'),
    };
  }

  get nodeConfigs(): {
    [key: string]: NodeConfig;
  } {
    const additionalSvNodes: { [key: string]: NodeConfig } = envFlag('ENABLE_TEST_SVS')
      ? {
          'sv-5': {
            id: '205437468610305149d131bbf9bf1f47658d861b',
            privateKey:
              'tlDwOSTLO1tAz9qfnTTUFFwVzJmtI7qn37rsoRbHPMRW2YWZ+53OXReOPSFzG/4pUCk4zxd1GJgb2ePFiDNlQQ==',
            identifier: this.nodeIdentifier,
            externalAddress: this.p2pExternalAddress(5),
            istioPort: this.istioExternalPort(5),
            validator: {
              keyAddress: '1A6C9E60AFD830682CBEF5496F6E5515B20B0F2D',
              privateKey:
                'kQtt4AjOpT4Nz79PZIP1eJ7o7o09R7AIzRnbLV1VN9rKTKnOZMl6LnD4OI0zrucJ9vToUykeWJhTsVenENgmBg==',
              publicKey: 'ykypzmTJei5w+DiNM67nCfb06FMpHliYU7FXpxDYJgY=',
            },
          },
          'sv-6': {
            id: '60c21490e82d6a1fb0c35b9a04e4f64ae00ce5c0',
            privateKey:
              'CtgCJPhaF9SzNIl0jMP18BIM9DseWY4Dlc8fRgBh2ygswJBWtSCcJT4YQBXrjQlpQMMl8gaYg8sK2+bbMw0LMw==',
            identifier: this.nodeIdentifier,
            externalAddress: this.p2pExternalAddress(6),
            istioPort: this.istioExternalPort(6),
            validator: {
              keyAddress: 'DC41F08916D8C41B931F9037E6F2571C58D0E01A',
              privateKey:
                'RlLIq1RqLGHh2a9XG3BtXRstOmw4avIFA3WzVJaGsXjAAUSM7xfipoPp1EzVNK9aNf5IxegsSogiOqpZSLURMg==',
              publicKey: 'wAFEjO8X4qaD6dRM1TSvWjX+SMXoLEqIIjqqWUi1ETI=',
            },
          },
          'sv-7': {
            id: '81f3b7d26ae796d369fbf42481a65c6265b41e8c',
            privateKey:
              'VQBBlN8oYVR4vxr8OjF/Q2YTKJVBCpa/048YDp8Gn2PtugPDFiJVxcpZ2ozkQsQ+CXl4IVEmhpLshRO/QVWz9g==',
            identifier: this.nodeIdentifier,
            externalAddress: this.p2pExternalAddress(7),
            istioPort: this.istioExternalPort(7),
            validator: {
              keyAddress: '66FA9399FF2E7AF2517E7CE2EDCA11F51C573F61',
              privateKey:
                'Vj9Z0txjW+4MM8AcnDezkQw+tLeJ0jKRRsj5xX9mvmxpZZJGAgAlJzek9rPz3O7bITKpG4pjlJjwS+m3+SW3vg==',
              publicKey: 'aWWSRgIAJSc3pPaz89zu2yEyqRuKY5SY8Evpt/klt74=',
            },
          },
          'sv-8': {
            id: '404371a5f62773ca07925555c9fbb6287861947c',
            privateKey:
              '8/AM1nf0Hf5nJre5cBTfLhCmUG6YfFNCBaXrBJNi0pYSEzTcRJ5LNZKgQpP5a9aVYzQmCVVlaV2nfOJKzTWmFA==',
            identifier: this.nodeIdentifier,
            externalAddress: this.p2pExternalAddress(8),
            istioPort: this.istioExternalPort(8),
            validator: {
              keyAddress: '5E35AE8D464FA92525BCC408C7827A943BDF4900',
              privateKey:
                '0IuhH2UTzhzYPbF3AQaSwp4WaHOYo/65Jr7lvxeAfqn9b9t8YL1LRV4q3HkgP0cUngk7x1Judj/ATwn7IRI7Fg==',
              publicKey: '/W/bfGC9S0VeKtx5ID9HFJ4JO8dSbnY/wE8J+yESOxY=',
            },
          },
          'sv-9': {
            id: 'aeee969d0efb0784ea36b9ad743a2e5964828325',
            privateKey:
              'a5d4ZHxQkazrjZH3R6TVZYIFkBoWflC/RmkQCmhhRInWm7Ikj9wBEvdKJuPEWv78MSmOLi3pJuYchkKkbwcvrA==',
            identifier: this.nodeIdentifier,
            externalAddress: this.p2pExternalAddress(9),
            istioPort: this.istioExternalPort(9),
            validator: {
              keyAddress: '06070D2FD47073BE1635C3DEB862A88669906847',
              privateKey:
                'yDch3FNnIHFJPGlUpOYecAtxlwAi3QBi7dELVC/ON3muR3ekkf6TCsO32Lxvcj1zVGrOeewV7k+54TeAHmdmDw==',
              publicKey: 'rkd3pJH+kwrDt9i8b3I9c1RqznnsFe5PueE3gB5nZg8=',
            },
          },
        }
      : {};

    return {
      'sv-1': this.sv1NodeConfig,
      'sv-2': {
        id: 'c36b3bbd969d993ba0b4809d1f587a3a341f22c1',
        privateKey:
          '1Je33z2g+Dj2UWLqnsO+xwUwbalIS0LLcYAoj+fYuEE2le4kJjJ0h+L7FfVg+3mbgvrikdke91I2X5C2frj0Eg==',
        identifier: this.nodeIdentifier,
        externalAddress: this.p2pExternalAddress(2),
        istioPort: this.istioExternalPort(2),
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
        identifier: this.nodeIdentifier,
        externalAddress: this.p2pExternalAddress(3),
        istioPort: this.istioExternalPort(3),
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
        identifier: this.nodeIdentifier,
        retainBlocks: isDevNet ? 10000 : 700000, // sv4 starts pruning after 2 hours on devnet only for testing purposes
        externalAddress: this.p2pExternalAddress(4),
        istioPort: this.istioExternalPort(4),
        validator: {
          keyAddress: 'DE36D23DE022948A11200ABB9EE07F049D17D903',
          privateKey:
            'nUSWALRjErKf+/SVI93LU5venfN/YvsLaVdLYhbYPFPa6Zl1RL3trpVRcwayAon9VtBtqfFZoVTErVCKaGUSOg==',
          publicKey: '2umZdUS97a6VUXMGsgKJ/VbQbanxWaFUxK1QimhlEjo=',
        },
      },
      ...additionalSvNodes,
    };
  }

  private p2pExternalAddress(nodeIndex: DomainIndex): string {
    return `${CLUSTER_DNS_NAME}:${this.istioExternalPort(nodeIndex)}`;
  }

  private istioExternalPort(nodeIndex: DomainIndex) {
    return Number(`26${this._domain}${nodeIndex}6`);
  }
}
