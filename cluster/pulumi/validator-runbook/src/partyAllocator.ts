// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  CnInput,
  DecentralizedSynchronizerUpgradeConfig,
  ExactNamespace,
  InstalledHelmChart,
  installSpliceHelmChart,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { PartyAllocatorConfig } from '@lfdecentralizedtrust/splice-pulumi-common-validator';

export function installPartyAllocator(
  xns: ExactNamespace,
  config: PartyAllocatorConfig,
  dependsOn: CnInput<pulumi.Resource>[]
): InstalledHelmChart {
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
    },
    activeVersion,
    { dependsOn }
  );
}
