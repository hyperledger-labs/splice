// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DecentralizedSynchronizerMigrationConfig,
  exactNamespace,
  imagePullSecret,
  installLedgerApiSecret,
  type Auth0Client,
} from '@canton-network/splice-pulumi-common';
import { dSyncMigrationConfig, SynchronizerNodes } from '@canton-network/splice-pulumi-common-sv';

export async function installNode(auth0Client: Auth0Client, namespace: string): Promise<void> {
  const xns = exactNamespace(namespace, true);
  imagePullSecret(xns);
  await installLedgerApiSecret(auth0Client, xns, 'dsync');

  new SynchronizerNodes(
    dSyncMigrationConfig,
    undefined, // cometbft is not supported for dsyncs
    namespace // reuse the namespace as the ingress name for dsyncs
  );
}
