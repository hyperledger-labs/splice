// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { AmuletConfig, RewardVersion } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { Tuple2 } from '@daml.js/daml-prim-DA-Types-1.0.0/lib/DA/Types';
import * as damlTypes from '@daml/types';
import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types';
import { IssuanceConfig } from '@daml.js/splice-amulet/lib/Splice/Issuance';
import { ConfigChange } from './types';
import { Set as DamlSet } from '@daml.js/daml-stdlib-DA-Set-Types-1.0.0/lib/DA/Set/Types';
import { configValueToSwitchOverMap } from '../components/forms/formValidators';

function lsToSet<T>(ls: T[]): DamlSet<T> {
  return {
    // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- This is actually representing Unit in Daml
    map: ls.reduce((acc, v) => acc.set(v, {}), damlTypes.emptyMap<T, {}>()),
  };
}

/**
 * Given a list of config changes, build and return an AmuletConfig<'USD'>.
 * The config changes should have all fields, whether they have been changed or not.
 */
export function buildAmuletRulesConfigFromChanges(
  amuletConfigChanges: ConfigChange[]
): AmuletConfig<'USD'> {
  const changeMap = new Map<string, string>();
  amuletConfigChanges.forEach(change => {
    changeMap.set(change.fieldName, change.newValue);
  });

  function getValue(fieldName: string, nullable: true): string | null;
  function getValue(fieldName: string, nullable: false): string;
  function getValue(fieldName: string, nullable: boolean): string | null {
    const value = changeMap.get(fieldName);
    return value === undefined || value === '' ? (nullable ? null : '') : value;
  }

  const getArrayCount = (prefix: string, isPairs = false) => {
    const keysCount = Array.from(changeMap.keys()).filter(key => key.startsWith(prefix)).length;

    return isPairs ? keysCount / 2 : keysCount;
  };

  const transferFeeStepsCount = getArrayCount('transferFeeSteps', true);
  const transferFeeSteps: Tuple2<string, string>[] = [];
  for (let i = 1; i <= transferFeeStepsCount; i++) {
    const _1 = getValue(`transferFeeSteps${i}_1`, false);
    const _2 = getValue(`transferFeeSteps${i}_2`, false);
    if (_1 && _2) {
      transferFeeSteps.push({ _1, _2 });
    }
  }

  const numRequiredSynchronizers = getArrayCount('decentralizedSynchronizerRequiredSynchronizers');
  const requiredSynchronizers: string[] = [];
  for (let i = 1; i <= numRequiredSynchronizers; i++) {
    requiredSynchronizers.push(
      getValue(`decentralizedSynchronizerRequiredSynchronizers${i}`, false)
    );
  }
  const requiredSynchronizersSet = lsToSet(requiredSynchronizers);

  const futureValuesCount = Array.from(changeMap.keys()).filter(key =>
    key.match(/^issuanceCurveFutureValues\d$/)
  ).length;
  const futureValues: Tuple2<RelTime, IssuanceConfig>[] = [];
  for (let i = 0; i < futureValuesCount; i++) {
    const time = { microseconds: getValue(`issuanceCurveFutureValues${i}`, false) };
    const config: IssuanceConfig = {
      amuletToIssuePerYear: getValue(`issuanceCurveFutureValues${i}AmuletToIssuePerYear`, false),
      validatorRewardPercentage: getValue(
        `issuanceCurveFutureValues${i}ValidatorRewardPercentage`,
        false
      ),
      appRewardPercentage: getValue(`issuanceCurveFutureValues${i}AppRewardPercentage`, false),
      validatorRewardCap: getValue(`issuanceCurveFutureValues${i}ValidatorRewardCap`, false),
      featuredAppRewardCap: getValue(`issuanceCurveFutureValues${i}FeaturedAppRewardCap`, false),
      unfeaturedAppRewardCap: getValue(
        `issuanceCurveFutureValues${i}UnfeaturedAppRewardCap`,
        false
      ),
      optValidatorFaucetCap: getValue(`issuanceCurveFutureValues${i}OptValidatorFaucetCap`, true),
      optDevelopmentFundPercentage: getValue(
        `issuanceCurveFutureValues${i}OptDevelopmentFundPercentage`,
        true
      ),
    };
    futureValues.push({ _1: time, _2: config });
  }

  const externalPartyConfigStateTickDuration = getValue(
    'externalPartyConfigStateTickDuration',
    true
  );
  const transferConfigTokenStandardMaxTTL = getValue('transferConfigTokenStandardMaxTTL', true);
  const developmentFundManagerBlacklistParties =
    getValue('developmentFundManagerBlacklist', true)
      ?.split(',')
      .map(party => party.trim())
      .filter(party => party !== '') ?? [];
  const developmentFundManagerBlacklist =
    developmentFundManagerBlacklistParties.length > 0
      ? developmentFundManagerBlacklistParties
      : null;
  const minDevelopmentFundMintingDelay = getValue('minDevelopmentFundMintingDelay', true);
  const rewardConfigMintingVersion = getValue('rewardConfigMintingVersion', true);

  const amuletSwitchOverTimes = configValueToSwitchOverMap(getValue('amuletSwitchOverTimes', true));

  const amuletConfig: AmuletConfig<'USD'> = {
    tickDuration: { microseconds: getValue('tickDuration', false) },
    transferPreapprovalFee: getValue('transferPreapprovalFee', true),
    featuredAppActivityMarkerAmount: getValue('featuredAppActivityMarkerAmount', true),
    optDevelopmentFundManager: getValue('optDevelopmentFundManager', true),
    externalPartyConfigStateTickDuration:
      externalPartyConfigStateTickDuration === null
        ? null
        : { microseconds: externalPartyConfigStateTickDuration },
    transferPreapprovalBaseDuration: null,
    developmentFundManagerBlacklist,
    minDevelopmentFundMintingDelay:
      minDevelopmentFundMintingDelay === null
        ? null
        : { microseconds: minDevelopmentFundMintingDelay },
    amuletSwitchOverTimes: amuletSwitchOverTimes,
    transferConfig: {
      createFee: { fee: getValue('transferConfigCreateFee', false) },
      holdingFee: { rate: getValue('transferConfigHoldingFeeRate', false) },
      transferFee: {
        initialRate: getValue('transferConfigTransferFeeInitialRate', false),
        steps: transferFeeSteps,
      },
      lockHolderFee: { fee: getValue('transferConfigLockHolderFee', false) },
      extraFeaturedAppRewardAmount: getValue('transferConfigExtraFeaturedAppRewardAmount', false),
      maxNumInputs: getValue('transferConfigMaxNumInputs', false),
      maxNumOutputs: getValue('transferConfigMaxNumOutputs', false),
      maxNumLockHolders: getValue('transferConfigMaxNumLockHolders', false),
      tokenStandardMaxTTL:
        transferConfigTokenStandardMaxTTL && transferConfigTokenStandardMaxTTL !== ''
          ? { microseconds: transferConfigTokenStandardMaxTTL }
          : null,
    },

    issuanceCurve: {
      initialValue: {
        amuletToIssuePerYear: getValue('issuanceCurveInitialValueAmuletToIssuePerYear', false),
        validatorRewardPercentage: getValue(
          'issuanceCurveInitialValueValidatorRewardPercentage',
          false
        ),
        appRewardPercentage: getValue('issuanceCurveInitialValueAppRewardPercentage', false),
        validatorRewardCap: getValue('issuanceCurveInitialValueValidatorRewardCap', false),
        featuredAppRewardCap: getValue('issuanceCurveInitialValueFeaturedAppRewardCap', false),
        unfeaturedAppRewardCap: getValue('issuanceCurveInitialValueUnfeaturedAppRewardCap', false),
        optValidatorFaucetCap: getValue('issuanceCurveInitialValueOptValidatorFaucetCap', true),
        optDevelopmentFundPercentage: getValue(
          'issuanceCurveInitialValueOptDevelopmentFundPercentage',
          true
        ),
      },
      futureValues: futureValues,
    },

    decentralizedSynchronizer: {
      activeSynchronizer: getValue('decentralizedSynchronizerActiveSynchronizer', false),
      requiredSynchronizers: requiredSynchronizersSet,
      fees: {
        baseRateTrafficLimits: {
          burstAmount: getValue(
            'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstAmount',
            false
          ),
          burstWindow: {
            microseconds: getValue(
              'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstWindow',
              false
            ),
          },
        },
        extraTrafficPrice: getValue('decentralizedSynchronizerFeesExtraTrafficPrice', false),
        readVsWriteScalingFactor: getValue(
          'decentralizedSynchronizerFeesReadVsWriteScalingFactor',
          false
        ),
        minTopupAmount: getValue('decentralizedSynchronizerFeesMinTopupAmount', false),
      },
    },

    packageConfig: {
      amulet: getValue('packageConfigAmulet', false),
      amuletNameService: getValue('packageConfigAmuletNameService', false),
      dsoGovernance: getValue('packageConfigDsoGovernance', false),
      validatorLifecycle: getValue('packageConfigValidatorLifecycle', false),
      wallet: getValue('packageConfigWallet', false),
      walletPayments: getValue('packageConfigWalletPayments', false),
    },

    rewardConfig:
      rewardConfigMintingVersion === null
        ? null
        : {
            mintingVersion: rewardConfigMintingVersion as RewardVersion,
            dryRunVersion: getValue('rewardConfigDryRunVersion', true) as RewardVersion | null,
            batchSize: getValue('rewardConfigBatchSize', false),
            rewardCouponTimeToLive: {
              microseconds: getValue('rewardConfigRewardCouponTimeToLive', false),
            },
            appRewardCouponThreshold: getValue('rewardConfigAppRewardCouponThreshold', false),
          },
  };

  return amuletConfig;
}
