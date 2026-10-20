// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { DecentralizedSynchronizerMigrationConfig } from '@canton-network/splice-pulumi-common';

import {
  CrossStackDecentralizedSynchronizerNode,
  DecentralizedSynchronizerNode,
  SvParticipant,
} from './decentralizedSynchronizerNode';

export class SynchronizerNodes {
  readonly participant: SvParticipant;
  readonly active: DecentralizedSynchronizerNode;
  readonly legacy?: DecentralizedSynchronizerNode;
  readonly additionalLegacy: DecentralizedSynchronizerNode[];
  readonly upgrade?: DecentralizedSynchronizerNode;

  constructor(
    decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
    ingressName: string
  ) {
    this.participant = {
      internalClusterAddress: pulumi.output('participant'),
    };

    this.active = new CrossStackDecentralizedSynchronizerNode(
      decentralizedSynchronizerMigrationConfig.active.id,
      ingressName
    );

    if (decentralizedSynchronizerMigrationConfig.legacy) {
      this.legacy = new CrossStackDecentralizedSynchronizerNode(
        decentralizedSynchronizerMigrationConfig.legacy.id,
        ingressName
      );
    }

    this.additionalLegacy = decentralizedSynchronizerMigrationConfig.additionalLegacy.map(
      info => new CrossStackDecentralizedSynchronizerNode(info.id, ingressName)
    );

    if (decentralizedSynchronizerMigrationConfig.upgrade) {
      this.upgrade = new CrossStackDecentralizedSynchronizerNode(
        decentralizedSynchronizerMigrationConfig.upgrade.id,
        ingressName
      );
    }
  }
}
