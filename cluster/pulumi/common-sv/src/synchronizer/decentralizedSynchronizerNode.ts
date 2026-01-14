// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_HOSTNAME, DomainMigrationIndex } from '@lfdecentralizedtrust/splice-pulumi-common';
import { Resource } from '@pulumi/pulumi';

export interface SvParticipant {
  readonly asDependencies: pulumi.Resource[];
  readonly internalClusterAddress: pulumi.Output<string>;
}

export interface CantonBftSynchronizerNode {
  externalSequencerP2pAddress: string;
}

export interface CometbftSynchronizerNode {
  cometbftRpcServiceName: string;
}

export interface DecentralizedSynchronizerNode {
  migrationId: number;
  readonly namespaceInternalSequencerAddress: string;
  readonly namespaceInternalMediatorAddress: string;
  readonly sv1InternalSequencerAddress: string;
  readonly dependencies: pulumi.Resource[];
}

export type InstalledMigrationSpecificSv = {
  decentralizedSynchronizer: DecentralizedSynchronizerNode;
  participant: SvParticipant;
};

export class CrossStackDecentralizedSynchronizerNode
  implements DecentralizedSynchronizerNode, CantonBftSynchronizerNode
{
  name: string;
  migrationId: number;
  ingressName: string;

  get externalSequencerP2pAddress(): string {
    return `https://sequencer-p2p-${this.migrationId}.${this.ingressName}.${CLUSTER_HOSTNAME}`;
  }

  constructor(migrationId: DomainMigrationIndex, ingressName: string) {
    this.migrationId = migrationId;
    this.name = 'global-domain-' + migrationId.toString();
    this.ingressName = ingressName;
  }

  get namespaceInternalSequencerAddress(): string {
    return `${this.name}-sequencer`;
  }

  get namespaceInternalMediatorAddress(): string {
    return `${this.name}-mediator`;
  }

  get sv1InternalSequencerAddress(): string {
    return `http://${this.namespaceInternalSequencerAddress}.sv-1:5008`;
  }

  readonly dependencies: Resource[] = [];
}

export class CrossStackCometBftDecentralizedSynchronizerNode
  extends CrossStackDecentralizedSynchronizerNode
  implements CometbftSynchronizerNode
{
  cometbftRpcServiceName: string;

  constructor(
    migrationId: DomainMigrationIndex,
    cometbftNodeIdentifier: string,
    ingressName: string
  ) {
    super(migrationId, ingressName);
    this.cometbftRpcServiceName = `${cometbftNodeIdentifier}-cometbft-rpc`;
  }
}
