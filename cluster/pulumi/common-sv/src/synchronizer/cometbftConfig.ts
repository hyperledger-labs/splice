// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from '@lfdecentralizedtrust/splice-pulumi-common/src/config';
import { Output } from '@pulumi/pulumi';

export const disableCometBftStateSync = config.envFlag('DISABLE_COMETBFT_STATE_SYNC', false);

export type StaticCometBftConfig = {
  privateKey?: Output<string> | string;
  validator: {
    keyAddress: Output<string> | string;
    privateKey?: Output<string> | string;
    publicKey?: Output<string> | string;
  };
  nodeIndex: number;
  id: string;
};

export interface StaticCometBftConfigWithNodeName extends StaticCometBftConfig {
  nodeName: string;
}

export function cometBFTExternalPort(migrationId: number, nodeIndex: number): number {
  // TODO(DACH-NY/canton-network-node#10482) Revisit port scheme
  return nodeIndex >= 10
    ? Number(`26${migrationId}${nodeIndex}`)
    : Number(`26${migrationId}${nodeIndex}6`);
}
