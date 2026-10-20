// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DeploySvRunbook, isDevNet, isMainNet } from '@canton-network/splice-pulumi-common';
import { SweepConfig } from '@canton-network/splice-pulumi-common-validator';
import { spliceEnvConfig } from '@canton-network/splice-pulumi-common/src/config/envConfig';

import { StaticSvConfig } from './config';
import { dsoSize, skipExtraSvs } from './dsoConfig';
import { configForSv, configuredExtraSvs } from './singleSvConfig';
import {
  fromSingleSvConfigBasic,
  standardSvConfigsBasic,
  svRunbookConfigBasic,
} from './svConfigsBasic';

// TODO(#1892): we can think about moving all of the values here to config.yaml ; it's a bit risky/expensive to test though
const fromSingleSvConfig = (nodeName: string): StaticSvConfig => {
  const config = configForSv(nodeName);

  return {
    ...fromSingleSvConfigBasic(nodeName),
    auth0ValidatorAppName: config.validatorApp?.auth0?.name
      ? config.validatorApp.auth0.name
      : `${nodeName}_validator`,
    auth0SvAppName: config.svApp?.auth0?.name ? config.svApp.auth0.name : nodeName,
    validatorWalletUser: config.validatorApp?.walletUser,
    svIdKeySecretName: config.svApp?.svIdKeyGcpSecret,
    cometBftGovernanceKeySecretName: config.svApp?.cometBftGovernanceKeyGcpSecret,
  };
};

export const standardSvConfigs: StaticSvConfig[] = (
  isMainNet
    ? [
        {
          auth0ValidatorAppName: 'validator',
          auth0SvAppName: 'sv',
        },
      ]
    : [
        {
          auth0ValidatorAppName: 'sv1_validator',
          auth0SvAppName: 'sv-1',
          validatorWalletUser: isDevNet
            ? 'auth0|64afbc0956a97fe9577249d7'
            : 'auth0|64529b128448ded6aa68048f',
        },
        {
          auth0ValidatorAppName: 'sv2_validator',
          auth0SvAppName: 'sv-2',
          validatorWalletUser: 'auth0|64afbc353bbc7ca776e27bf4',
        },
        {
          auth0ValidatorAppName: 'sv3_validator',
          auth0SvAppName: 'sv-3',
          validatorWalletUser: 'auth0|64afbc4431b562edb8995da6',
        },
        {
          auth0ValidatorAppName: 'sv4_validator',
          auth0SvAppName: 'sv-4',
          validatorWalletUser: 'auth0|64afbc720e20777e46fff490',
        },
        {
          auth0ValidatorAppName: 'sv5_validator',
          auth0SvAppName: 'sv-5',
          validatorWalletUser: 'auth0|65c15c482a18b1ef030ba290',
        },
        {
          auth0ValidatorAppName: 'sv6_validator',
          auth0SvAppName: 'sv-6',
          validatorWalletUser: 'auth0|65c26e959666d60d24fe523a',
        },
        {
          auth0ValidatorAppName: 'sv7_validator',
          auth0SvAppName: 'sv-7',
          validatorWalletUser: 'auth0|65c26e9d45eaef5c191a167e',
        },
        {
          auth0ValidatorAppName: 'sv8_validator',
          auth0SvAppName: 'sv-8',
          validatorWalletUser: 'auth0|65c26ea449ef8564a0ec9297',
        },
        {
          auth0ValidatorAppName: 'sv9_validator',
          auth0SvAppName: 'sv-9',
          validatorWalletUser: 'auth0|65c26eac58f141b4ca1dc5da',
        },
        {
          auth0ValidatorAppName: 'sv10_validator',
          auth0SvAppName: 'sv-10',
          validatorWalletUser: 'auth0|65e0a7854c76b74b28b8477f',
        },
        {
          auth0ValidatorAppName: 'sv11_validator',
          auth0SvAppName: 'sv-11',
          validatorWalletUser: 'auth0|65e0a78976d9757e3f14846b',
        },
        {
          auth0ValidatorAppName: 'sv12_validator',
          auth0SvAppName: 'sv-12',
          validatorWalletUser: 'auth0|65e0a78d68c39e5cc0351ed2',
        },
        {
          auth0ValidatorAppName: 'sv13_validator',
          auth0SvAppName: 'sv-13',
          validatorWalletUser: 'auth0|65e0a7914c76b74b28b84793',
        },
        {
          auth0ValidatorAppName: 'sv14_validator',
          auth0SvAppName: 'sv-14',
          validatorWalletUser: 'auth0|65e0a795aa7a40df0cc65ace',
        },
        {
          auth0ValidatorAppName: 'sv15_validator',
          auth0SvAppName: 'sv-15',
          validatorWalletUser: 'auth0|65e0a7994c76b74b28b8479c',
        },
        {
          auth0ValidatorAppName: 'sv16_validator',
          auth0SvAppName: 'sv-16',
          validatorWalletUser: 'auth0|65e0a79de124e5c43dcb6a19',
        },
      ]
).map((e, i) => ({ ...e, ...standardSvConfigsBasic[i] }));

// TODO(#1892): consider supporting overrides of hardcoded svs (in case we're keeping hardcoded svs at all)
export const extraSvConfigs: StaticSvConfig[] = configuredExtraSvs.map(k => fromSingleSvConfig(k));

export const svConfigs = standardSvConfigs.concat(extraSvConfigs);

export const sv1Config: StaticSvConfig = standardSvConfigs[0];

export const svRunbookConfig: StaticSvConfig = {
  ...svRunbookConfigBasic,
  auth0SvAppName: 'sv',
  auth0ValidatorAppName: 'validator',
  // Default to admin@sv-dev.com (devnet) or admin@sv.com (non devnet) at the sv-test tenant by default
  validatorWalletUser: isDevNet
    ? 'auth0|64b16b9ff7a0dfd00ea3704e'
    : 'auth0|64553aa683015a9687d9cc2e',
};

export function sweepConfigFromEnv(nodeName: string): SweepConfig | undefined {
  const asJson = spliceEnvConfig.optionalEnv(`${nodeName}_SWEEP`);
  return asJson && JSON.parse(asJson);
}

// "core SVs" are deployed as part of the `canton-network` stack;
// if config.yaml contains any SVs that don't match the standard sv-X pattern, we deploy them independently of DSO_SIZE
export const coreSvsToDeploy: StaticSvConfig[] = standardSvConfigs
  .slice(0, dsoSize)
  .concat(skipExtraSvs ? [] : extraSvConfigs);

export const allSvsToDeploy = coreSvsToDeploy.concat(DeploySvRunbook ? [svRunbookConfig] : []);
