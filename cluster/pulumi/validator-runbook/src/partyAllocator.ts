// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  CnInput,
  createVolumeSnapshot,
  DecentralizedSynchronizerUpgradeConfig,
  ExactNamespace,
  InstalledHelmChart,
  installSpliceHelmChart,
  standardStorageClassName,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { PartyAllocatorConfig } from '@lfdecentralizedtrust/splice-pulumi-common-validator';

import { hyperdiskSupportConfig } from '../../common/src/config/hyperdiskSupportConfig';

export function installPartyAllocator(
  xns: ExactNamespace,
  config: PartyAllocatorConfig,
  dependsOn: CnInput<pulumi.Resource>[]
): InstalledHelmChart {
  const dataSource =
    hyperdiskSupportConfig.hyperdiskSupport.enabled &&
    hyperdiskSupportConfig.hyperdiskSupport.migrating
      ? {
          dataSource: createVolumeSnapshot({
            resourceName: `party-allocator-keys-migration-snapshot`,
            snapshotName: `party-allocator-keys-snapshot`,
            namespace: xns.logicalName,
            pvcName: `party-allocator-keys`,
          }).dataSource,
        }
      : {};
  return installSpliceHelmChart(
    xns,
    'party-allocator',
    'splice-party-allocator',
    {
      config: {
        token: '${SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_TOKEN}',
        userId: '${SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME}',
        jsonLedgerApiUrl: `http://participant-${DecentralizedSynchronizerUpgradeConfig.active.id}:7575`,
        scanApiUrl: 'http://scan-app.sv-1:5012',
        validatorApiUrl: 'http://validator-app:5003',
        maxParties: config.maxParties,
        keyDirectory: '/keys',
        parallelism: config.parallelism,
        preapprovalRetries: config.preapprovalRetries,
        preapprovalRetryDelayMs: config.preapprovalRetryDelayMs,
      },
      pvc: {
        ...(config.pvcSize ? { size: config.pvcSize } : {}),
        volumeStorageClass: standardStorageClassName,
        name: hyperdiskSupportConfig.hyperdiskSupport.enabled
          ? 'party-allocator-keys-hd-pvc'
          : 'party-allocator-keys',
        ...dataSource,
      },
    },
    activeVersion,
    { dependsOn }
  );
}
