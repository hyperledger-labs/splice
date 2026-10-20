// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from '@canton-network/splice-pulumi-common';

export function getChainIdSuffix(): string {
  return config.optionalEnv('COMETBFT_CHAIN_ID_SUFFIX') || '0';
}
