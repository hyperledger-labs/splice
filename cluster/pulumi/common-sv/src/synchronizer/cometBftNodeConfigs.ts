// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Lifted, OutputInstance } from '@pulumi/pulumi';
import { CLUSTER_HOSTNAME } from 'splice-pulumi-common/src/utils';

import { StaticCometBftConfig, StaticCometBftConfigWithNodeName } from './cometbftConfig';

export interface CometBftNodeConfig extends Omit<StaticCometBftConfig, 'nodeIndex'> {
  istioPort: number;
  externalAddress: string;
  identifier: string;
}

export class CometBftNodeConfigs {
  private readonly _domainMigrationId: number;
  private readonly _nodeConfigs: {
    self: StaticCometBftConfigWithNodeName;
    sv1: StaticCometBftConfigWithNodeName;
    peers: StaticCometBftConfigWithNodeName[];
  };

  constructor(
    domainMigrationId: number,
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    }
  ) {
    this._domainMigrationId = domainMigrationId;
    this._nodeConfigs = nodeConfigs;
  }

  private staticToNodeConfig(staticConf: StaticCometBftConfigWithNodeName): CometBftNodeConfig {
    return {
      id: staticConf.id,
      privateKey: staticConf.privateKey,
      identifier: this.nodeIdentifier,
      externalAddress: this.p2pExternalAddress(staticConf.nodeIndex),
      istioPort: istioCometbftExternalPort(this._domainMigrationId, staticConf.nodeIndex),
      retainBlocks: staticConf.retainBlocks,
      validator: staticConf.validator,
    };
  }

  get self(): CometBftNodeConfig {
    return this.staticToNodeConfig(this._nodeConfigs.self);
  }

  get selfSvNodeName(): string {
    return this._nodeConfigs.self.nodeName;
  }

  get sv1NodeConfig(): CometBftNodeConfig {
    return this.staticToNodeConfig(this._nodeConfigs.sv1);
  }

  p2pServiceAddress(nodeId: string): string {
    return `${this.nodeIdentifier}-cometbft-p2p.${this._nodeConfigs.peers.concat(this._nodeConfigs.sv1).find(peer => peer.id === nodeId)?.nodeName}.svc.cluster.local:26656`;
  }

  get nodeIdentifier(): string {
    return `global-domain-${this._domainMigrationId}-cometbft`;
  }

  get sv1(): {
    keyAddress: (OutputInstance<string> & Lifted<string>) | string | undefined;
    externalAddress: string;
    publicKey: (OutputInstance<string> & Lifted<string>) | string | undefined;
    nodeId: string;
  } {
    return {
      nodeId: this.sv1NodeConfig.id,
      publicKey: this.sv1NodeConfig.validator.publicKey,
      keyAddress: this.sv1NodeConfig.validator.keyAddress,
      externalAddress: this.p2pExternalAddress(this._nodeConfigs.sv1.nodeIndex),
    };
  }

  get peers(): CometBftNodeConfig[] {
    return this._nodeConfigs.peers.map(peer => this.staticToNodeConfig(peer));
  }

  private p2pExternalAddress(nodeIndex: number): string {
    return `${CLUSTER_HOSTNAME}:${istioCometbftExternalPort(this._domainMigrationId, nodeIndex)}`;
  }
}

export const istioCometbftExternalPort = (migrationId: number, nodeIndex: number): number => {
  // TODO(DACH-NY/canton-network-node#10482) Revisit port scheme
  return nodeIndex >= 10
    ? Number(`26${migrationId}${nodeIndex}`)
    : Number(`26${migrationId}${nodeIndex}6`);
};
