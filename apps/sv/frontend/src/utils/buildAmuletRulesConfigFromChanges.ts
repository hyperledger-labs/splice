// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { AmuletConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { Tuple2 } from '@daml.js/daml-prim-DA-Types-1.0.0/lib/DA/Types';
import * as damlTypes from '@daml/types';
import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types';
import { IssuanceConfig } from '@daml.js/splice-amulet/lib/Splice/Issuance';
import { ConfigChange } from './types';
import { Set as DamlSet } from '@daml.js/daml-stdlib-DA-Set-Types-1.0.0/lib/DA/Set/Types';

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
    changeMap.set(change.fieldName, change.newValue.toString());
  });

  const getValue = (fieldName: string, fallbackValue: string = '') => {
    const value = changeMap.get(fieldName);
    return value ? value : fallbackValue;
  };

  const getArrayCount = (prefix: string, isPairs = false) => {
    const keysCount = Array.from(changeMap.keys()).filter(key => key.startsWith(prefix)).length;

    return isPairs ? keysCount / 2 : keysCount;
  };

  const transferFeeStepsCount = getArrayCount('transferFeeSteps', true);
  const transferFeeSteps: Tuple2<string, string>[] = [];
  for (let i = 1; i <= transferFeeStepsCount; i++) {
    const _1 = getValue(`transferFeeSteps${i}_1`);
    const _2 = getValue(`transferFeeSteps${i}_2`);
    if (_1 && _2) {
      transferFeeSteps.push({ _1, _2 });
    }
  }

  const numRequiredSynchronizers = getArrayCount('decentralizedSynchronizerRequiredSynchronizers');
  const requiredSynchronizers: string[] = [];
  for (let i = 1; i <= numRequiredSynchronizers; i++) {
    requiredSynchronizers.push(getValue(`decentralizedSynchronizerRequiredSynchronizers${i}`));
  }
  const requiredSynchronizersSet = lsToSet(requiredSynchronizers);

  const futureValuesCount = Array.from(changeMap.keys()).filter(key =>
    key.match(/^issuanceCurveFutureValues\d$/)
  ).length;
  const futureValues: Tuple2<RelTime, IssuanceConfig>[] = [];
  for (let i = 0; i < futureValuesCount; i++) {
    const time = { microseconds: getValue(`issuanceCurveFutureValues${i}`) };
    const config: IssuanceConfig = {
      amuletToIssuePerYear: getValue(`issuanceCurveFutureValues${i}AmuletToIssuePerYear`),
      validatorRewardPercentage: getValue(`issuanceCurveFutureValues${i}ValidatorRewardPercentage`),
      appRewardPercentage: getValue(`issuanceCurveFutureValues${i}AppRewardPercentage`),
      validatorRewardCap: getValue(`issuanceCurveFutureValues${i}ValidatorRewardCap`),
      featuredAppRewardCap: getValue(`issuanceCurveFutureValues${i}FeaturedAppRewardCap`),
      unfeaturedAppRewardCap: getValue(`issuanceCurveFutureValues${i}UnfeaturedAppRewardCap`),
      optValidatorFaucetCap: getValue(`issuanceCurveFutureValues${i}OptValidatorFaucetCap`),
      optDevelopmentFundPercentage: getValue(
        `issuanceCurveFutureValues${i}optDevelopmentFundPercentage`
      ),
    };
    futureValues.push({ _1: time, _2: config });
  }

  const transferPreapprovalFee = getValue('transferPreapprovalFee');
  const amuletConfig: AmuletConfig<'USD'> = {
    tickDuration: { microseconds: getValue('tickDuration') },
    transferPreapprovalFee: transferPreapprovalFee === '' ? null : transferPreapprovalFee,
    featuredAppActivityMarkerAmount: getValue('featuredAppActivityMarkerAmount'),
    optDevelopmentFundManager: getValue('optDevelopmentFundManager'),

    transferConfig: {
      createFee: { fee: getValue('transferConfigCreateFee') },
      holdingFee: { rate: getValue('transferConfigHoldingFeeRate') },
      transferFee: {
        initialRate: getValue('transferConfigTransferFeeInitialRate'),
        steps: transferFeeSteps,
      },
      lockHolderFee: { fee: getValue('transferConfigLockHolderFee') },
      extraFeaturedAppRewardAmount: getValue('transferConfigExtraFeaturedAppRewardAmount'),
      maxNumInputs: getValue('transferConfigMaxNumInputs'),
      maxNumOutputs: getValue('transferConfigMaxNumOutputs'),
      maxNumLockHolders: getValue('transferConfigMaxNumLockHolders'),
    },

    issuanceCurve: {
      initialValue: {
        amuletToIssuePerYear: getValue('issuanceCurveInitialValueAmuletToIssuePerYear'),
        validatorRewardPercentage: getValue('issuanceCurveInitialValueValidatorRewardPercentage'),
        appRewardPercentage: getValue('issuanceCurveInitialValueAppRewardPercentage'),
        validatorRewardCap: getValue('issuanceCurveInitialValueValidatorRewardCap'),
        featuredAppRewardCap: getValue('issuanceCurveInitialValueFeaturedAppRewardCap'),
        unfeaturedAppRewardCap: getValue('issuanceCurveInitialValueUnfeaturedAppRewardCap'),
        optValidatorFaucetCap: getValue('issuanceCurveInitialValueOptValidatorFaucetCap'),
        optDevelopmentFundPercentage: getValue(
          'issuanceCurveInitialValueOptDevelopmentFundPercentage'
        ),
      },
      futureValues: futureValues,
    },

    decentralizedSynchronizer: {
      activeSynchronizer: getValue('decentralizedSynchronizerActiveSynchronizer'),
      requiredSynchronizers: requiredSynchronizersSet,
      fees: {
        baseRateTrafficLimits: {
          burstAmount: getValue('decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstAmount'),
          burstWindow: {
            microseconds: getValue('decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstWindow'),
          },
        },
        extraTrafficPrice: getValue('decentralizedSynchronizerFeesExtraTrafficPrice'),
        readVsWriteScalingFactor: getValue('decentralizedSynchronizerFeesReadVsWriteScalingFactor'),
        minTopupAmount: getValue('decentralizedSynchronizerFeesMinTopupAmount'),
      },
    },

    packageConfig: {
      amulet: getValue('packageConfigAmulet'),
      amuletNameService: getValue('packageConfigAmuletNameService'),
      dsoGovernance: getValue('packageConfigDsoGovernance'),
      validatorLifecycle: getValue('packageConfigValidatorLifecycle'),
      wallet: getValue('packageConfigWallet'),
      walletPayments: getValue('packageConfigWalletPayments'),
    },
  };

  return amuletConfig;
}
