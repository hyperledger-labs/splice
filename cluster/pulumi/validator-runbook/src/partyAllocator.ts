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

export function installPartyAllocator(
  xns: ExactNamespace,
  dependsOn: CnInput<pulumi.Resource>[]
): InstalledHelmChart {
  return installSpliceHelmChart(
    xns,
    'party-allocator',
    'splice-party-allocator',
    {
      jsonLedgerApiUrl: `http://participant-${DecentralizedSynchronizerUpgradeConfig.active.id}:7575`,
      scanApiUrl: 'http://scan-app.sv-1:5012',
      validatorApiUrl: 'http://validator-app:5003',
      maxParties: 1000000,
      keysDirectory: '/keys',
      parallelism: 5,
    },
    activeVersion,
    { dependsOn }
  );
}
