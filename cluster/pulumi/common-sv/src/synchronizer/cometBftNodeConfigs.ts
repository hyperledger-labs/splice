// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CLUSTER_HOSTNAME } from '@lfdecentralizedtrust/splice-pulumi-common/src/utils';
import { Lifted, OutputInstance } from '@pulumi/pulumi';

import {
  StaticCometBftConfig,
  StaticCometBftConfigWithNodeName,
  cometBFTExternalPort,
} from './cometbftConfig';

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
      istioPort: cometBFTExternalPort(this._domainMigrationId, staticConf.nodeIndex),
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
    return `cometbft.${CLUSTER_HOSTNAME}:${cometBFTExternalPort(this._domainMigrationId, nodeIndex)}`;
  }
}
