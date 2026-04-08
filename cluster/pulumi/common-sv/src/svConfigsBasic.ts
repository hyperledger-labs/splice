// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DeploySvRunbook, isMainNet } from '@lfdecentralizedtrust/splice-pulumi-common';
import { SweepConfig } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { spliceEnvConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig';

import { StaticSvConfigBasic } from './config';
import { dsoSize, skipExtraSvs } from './dsoConfig';
import { configForSv, configuredExtraSvs } from './singleSvConfig';

function sweepConfigFromEnv(nodeName: string): SweepConfig | undefined {
  const asJson = spliceEnvConfig.optionalEnv(`${nodeName}_SWEEP`);
  return asJson ? JSON.parse(asJson) : undefined;
}

export function fromSingleSvConfigBasic(nodeName: string): StaticSvConfigBasic {
  const config = configForSv(nodeName);
  return {
    nodeName,
    ingressName: config.subdomain!,
    onboardingName: config.publicName!,
    ...(config.validatorApp?.sweep
      ? { sweep: sweepConfigFromEnv(config.validatorApp.sweep.fromEnv) }
      : {}),
  };
}

// TODO(DACH-NY/canton-network-node#12169): consider making nodeName and ingressName the same (also for all other SVs)
export const standardSvConfigsBasic: StaticSvConfigBasic[] = isMainNet
  ? [
      {
        nodeName: 'sv-1',
        ingressName: 'sv-2',
        onboardingName: 'Digital-Asset-2',
        sweep: sweepConfigFromEnv('SV1'),
      },
    ]
  : [
      {
        nodeName: 'sv-1',
        ingressName: 'sv-2',
        onboardingName: 'Digital-Asset-2',
        sweep: sweepConfigFromEnv('SV1'),
      },
      { nodeName: 'sv-2', ingressName: 'sv-2-eng', onboardingName: 'Digital-Asset-Eng-2' },
      ...Array.from({ length: 14 }, (_, i) => ({
        nodeName: `sv-${i + 3}`,
        ingressName: `sv-${i + 3}-eng`,
        onboardingName: `Digital-Asset-Eng-${i + 3}`,
      })),
    ];

export const extraSvConfigsBasic: StaticSvConfigBasic[] = configuredExtraSvs.map(name =>
  fromSingleSvConfigBasic(name)
);

export const svRunbookConfigBasic: StaticSvConfigBasic = {
  onboardingName: 'DA-Helm-Test-Node',
  nodeName: 'sv',
  ingressName: 'sv',
};

export const svConfigsBasic = standardSvConfigsBasic.concat(extraSvConfigsBasic);

// "core SVs" are deployed as part of the `infra` stack;
// if config.yaml contains any SVs that don't match the standard sv-X pattern, we deploy them independently of DSO_SIZE
export const coreSvsToDeployBasic = standardSvConfigsBasic
  .slice(0, dsoSize)
  .concat(skipExtraSvs ? [] : extraSvConfigsBasic);

export const allSvsToDeployBasic = coreSvsToDeployBasic.concat(
  DeploySvRunbook ? [svRunbookConfigBasic] : []
);
