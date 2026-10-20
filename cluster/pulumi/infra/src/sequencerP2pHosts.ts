// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DecentralizedSynchronizerUpgradeConfig,
  getDnsNames,
} from '@canton-network/splice-pulumi-common';
import { allSvsToDeployBasic } from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';

/**
 * The hostnames under which the sequencer BFT P2P API is served, one per
 * (SV, running migration, cluster DNS name).
 *
 * These must stay in sync with the `p2pUrl`/`externalAddress` built in
 * `common-sv/src/synchronizer/decentralizedSynchronizerNode.ts`.
 */
export function sequencerP2pHosts(): string[] {
  const dnsNames = [getDnsNames().cantonDnsName, getDnsNames().daDnsName];
  return allSvsToDeployBasic.flatMap(sv =>
    DecentralizedSynchronizerUpgradeConfig.runningMigrations().flatMap(migration =>
      dnsNames.map(dns => `sequencer-p2p-${migration.id}.${sv.ingressName}.${dns}`)
    )
  );
}
