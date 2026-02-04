// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Optional } from '@daml/types';
import { AmuletConfig, PackageConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { Tuple2 } from '@daml.js/daml-prim-DA-Types-1.0.0/lib/DA/Types';
import { Set as DamlSet } from '@daml.js/daml-stdlib-DA-Set-Types-1.0.0/lib/DA/Set/Types';
import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types';
import { IssuanceConfig } from '@daml.js/splice-amulet/lib/Splice/Issuance';
import { Schedule } from '@daml.js/splice-amulet/lib/Splice/Schedule';
import { AmuletDecentralizedSynchronizerConfig } from '@daml.js/splice-amulet/lib/Splice/DecentralizedSynchronizer';
import { ConfigChange } from './types';

export function buildAmuletConfigChanges(
  before: Optional<AmuletConfig<'USD'>>,
  after: Optional<AmuletConfig<'USD'>>,
  showAllFields: boolean = false
): ConfigChange[] {
  const changes = [
    {
      fieldName: 'tickDuration',
      label: 'Round tick duration (microseconds)',
      currentValue: before?.tickDuration.microseconds || '',
      newValue: after?.tickDuration.microseconds || '',
    },
    {
      fieldName: 'transferPreapprovalFee',
      label: 'Transfer preapproval fee',
      currentValue: before?.transferPreapprovalFee || '',
      newValue: after?.transferPreapprovalFee || '',
    },
    {
      fieldName: 'featuredAppActivityMarkerAmount',
      label:
        'Amount of the AppRewardCoupon contract that a FeaturedAppActivityMarker is converted to (in USD)',
      currentValue: before?.featuredAppActivityMarkerAmount || '',
      newValue: after?.featuredAppActivityMarkerAmount || '',
    },
    {
      fieldName: 'optDevelopmentFundManager',
      label: 'Development fund manager',
      currentValue: before?.optDevelopmentFundManager || '',
      newValue: after?.optDevelopmentFundManager || '',
    },
    {
      fieldName: 'transferConfigCreateFee',
      label: 'Fee per created output contract in a transfer',
      currentValue: before?.transferConfig.createFee.fee || '',
      newValue: after?.transferConfig.createFee.fee || '',
    },
    {
      fieldName: 'transferConfigHoldingFeeRate',
      label: 'Holding fee per round',
      currentValue: before?.transferConfig.holdingFee?.rate || '',
      newValue: after?.transferConfig.holdingFee?.rate || '',
    },
    {
      fieldName: 'transferConfigTransferFeeInitialRate',
      label: 'Transfer fee',
      currentValue: before?.transferConfig.transferFee?.initialRate || '',
      newValue: after?.transferConfig.transferFee?.initialRate || '',
    },

    ...buildTransferFeeStepsChanges(
      before?.transferConfig.transferFee?.steps,
      after?.transferConfig.transferFee?.steps
    ),

    {
      fieldName: 'transferConfigLockHolderFee',
      label: 'Fee per lock holder output in a transfer',
      currentValue: before?.transferConfig.lockHolderFee.fee || '',
      newValue: after?.transferConfig.lockHolderFee.fee || '',
    },
    {
      fieldName: 'transferConfigExtraFeaturedAppRewardAmount',
      label: 'Amount of the AppRewardCoupon contract that is created per featured app transfer (in USD)',
      currentValue: before?.transferConfig.extraFeaturedAppRewardAmount || '',
      newValue: after?.transferConfig.extraFeaturedAppRewardAmount || '',
    },
    {
      fieldName: 'transferConfigMaxNumInputs',
      label: 'Max number of input contracts in a transfer',
      currentValue: before?.transferConfig.maxNumInputs || '',
      newValue: after?.transferConfig.maxNumInputs || '',
    },
    {
      fieldName: 'transferConfigMaxNumOutputs',
      label: 'Max number of outputs in a transfer',
      currentValue: before?.transferConfig.maxNumOutputs || '',
      newValue: after?.transferConfig.maxNumOutputs || '',
    },
    {
      fieldName: 'transferConfigMaxNumLockHolders',
      label: 'Max number of lock holders per locked coin created in a transfer',
      currentValue: before?.transferConfig.maxNumLockHolders || '',
      newValue: after?.transferConfig.maxNumLockHolders || '',
    },

    ...buildIssuanceCurveChanges(before?.issuanceCurve, after?.issuanceCurve),

    ...buildDecentralizedSynchronizerChanges(
      before?.decentralizedSynchronizer,
      after?.decentralizedSynchronizer
    ),

    ...buildPackageConfigChanges(before?.packageConfig, after?.packageConfig),
  ] as ConfigChange[];

  return showAllFields ? changes : changes.filter(c => c.currentValue !== c.newValue);
}

function buildPackageConfigChanges(
  before: PackageConfig | undefined,
  after: PackageConfig | undefined
) {
  if (!before && !after) return [];
  return [
    {
      fieldName: 'packageConfigAmulet',
      label: 'Daml model version: amulet',
      currentValue: before?.amulet || '',
      newValue: after?.amulet || '',
    },
    {
      fieldName: 'packageConfigAmuletNameService',
      label: 'Daml model version: amuletNameService',
      currentValue: before?.amuletNameService || '',
      newValue: after?.amuletNameService || '',
    },
    {
      fieldName: 'packageConfigDsoGovernance',
      label: 'Daml model version: dsoGovernance',
      currentValue: before?.dsoGovernance || '',
      newValue: after?.dsoGovernance || '',
    },
    {
      fieldName: 'packageConfigValidatorLifecycle',
      label: 'Daml model version: validatorLifecycle',
      currentValue: before?.validatorLifecycle || '',
      newValue: after?.validatorLifecycle || '',
    },
    {
      fieldName: 'packageConfigWallet',
      label: 'Daml model vVersion: wallet',
      currentValue: before?.wallet || '',
      newValue: after?.wallet || '',
    },
    {
      fieldName: 'packageConfigWalletPayments',
      label: 'Daml model version: walletPayments',
      currentValue: before?.walletPayments || '',
      newValue: after?.walletPayments || '',
    },
  ] as ConfigChange[];
}

function buildTransferFeeStepsChanges(
  before: Tuple2<string, string>[] | undefined,
  after: Tuple2<string, string>[] | undefined
) {
  return (
    before
      ?.map((b, i) => {
        const idx = i + 1;
        const a = after?.[idx];

        if (b._2 === '0.0') return [];

        return [
          {
            fieldName: `transferFeeSteps${idx}_1`,
            label: `Transfer fee step ${idx}`,
            currentValue: b._1,
            newValue: a?._1,
          },
          {
            fieldName: `transferFeeSteps${idx}_2`,
            label: `Transfer fee step ${idx}`,
            currentValue: b._2,
            newValue: a?._2,
          },
        ] as ConfigChange[];
      })
      .flat() || []
  );
}

function buildIssuanceCurveChanges(
  before: Schedule<RelTime, IssuanceConfig> | undefined,
  after: Schedule<RelTime, IssuanceConfig> | undefined
) {
  if (!before && !after) return [];

  const initialValues = [
    {
      fieldName: 'issuanceCurveInitialValueAmuletToIssuePerYear',
      label: 'Minting curve: Initial value: Amulet to issue per year',
      currentValue: before?.initialValue?.amuletToIssuePerYear || '',
      newValue: after?.initialValue?.amuletToIssuePerYear || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueValidatorRewardPercentage',
      label: 'Minting curve: Initial value: Validator reward percentage',
      currentValue: before?.initialValue?.validatorRewardPercentage || '',
      newValue: after?.initialValue?.validatorRewardPercentage || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueAppRewardPercentage',
      label: 'Minting curve: Initial value: App reward percentage',
      currentValue: before?.initialValue?.appRewardPercentage || '',
      newValue: after?.initialValue?.appRewardPercentage || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueValidatorRewardCap',
      label: 'Minting curve: Initial value: Validator reward cap',
      currentValue: before?.initialValue?.validatorRewardCap || '',
      newValue: after?.initialValue?.validatorRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueFeaturedAppRewardCap',
      label: 'Minting curve: Initial value: Featured app reward cap',
      currentValue: before?.initialValue?.featuredAppRewardCap || '',
      newValue: after?.initialValue?.featuredAppRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueUnfeaturedAppRewardCap',
      label: 'Minting curve: Initial value: Unfeatured app reward cap',
      currentValue: before?.initialValue?.unfeaturedAppRewardCap || '',
      newValue: after?.initialValue?.unfeaturedAppRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueOptValidatorFaucetCap',
      label: 'Minting curve: Initial value: Validator faucet cap',
      currentValue: before?.initialValue?.optValidatorFaucetCap || '',
      newValue: after?.initialValue?.optValidatorFaucetCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueOptDevelopmentFundPercentage',
      label: 'Minting curve: Initial value: Development fund percentage',
      currentValue: before?.initialValue?.optDevelopmentFundPercentage || '',
      newValue: after?.initialValue?.optDevelopmentFundPercentage || '',
    },
  ] as ConfigChange[];

  const futureValues =
    before?.futureValues
      .map((fv, idx) => {
        return [
          {
            fieldName: `issuanceCurveFutureValues${idx}`,
            label: `Minting curve: Step ${idx}: Start at round time (microseconds)`,
            currentValue: fv._1.microseconds || '',
            newValue: after?.futureValues[idx]._1.microseconds || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}AmuletToIssuePerYear`,
            label: `Minting curve: Step ${idx}: Amulet to issue per year`,
            currentValue: fv._2.amuletToIssuePerYear || '',
            newValue: after?.futureValues[idx]._2.amuletToIssuePerYear || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}ValidatorRewardPercentage`,
            label: `Minting curve: Step ${idx}: Validator reward percentage`,
            currentValue: fv._2.validatorRewardPercentage || '',
            newValue: after?.futureValues[idx]._2.validatorRewardPercentage || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}AppRewardPercentage`,
            label: `Minting curve: Step ${idx}: App reward percentage`,
            currentValue: fv._2.appRewardPercentage || '',
            newValue: after?.futureValues[idx]._2.appRewardPercentage || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}ValidatorRewardCap`,
            label: `Minting curve: Step ${idx}: Validator reward cap`,
            currentValue: fv._2.validatorRewardCap || '',
            newValue: after?.futureValues[idx]._2.validatorRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}FeaturedAppRewardCap`,
            label: `Minting curve: Step ${idx}: Featured app reward cap`,
            currentValue: fv._2.featuredAppRewardCap || '',
            newValue: after?.futureValues[idx]._2.featuredAppRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}UnfeaturedAppRewardCap`,
            label: `Minting curve: Step ${idx}: Unfeatured app reward cap`,
            currentValue: fv._2.unfeaturedAppRewardCap || '',
            newValue: after?.futureValues[idx]._2.unfeaturedAppRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}OptValidatorFaucetCap`,
            label: `Minting curve: Step ${idx}: Validator faucet cap`,
            currentValue: fv._2.optValidatorFaucetCap || '',
            newValue: after?.futureValues[idx]._2.optValidatorFaucetCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}OptDevelopmentFundPercentage`,
            label: `Minting curve: Step ${idx}: Development fund percentage`,
            currentValue: fv._2.optDevelopmentFundPercentage || '',
            newValue: after?.futureValues[idx]._2.optDevelopmentFundPercentage || '',
          },
        ] as ConfigChange[];
      })
      .flat() || [];

  return [...initialValues, ...futureValues];
}

function buildDecentralizedSynchronizerChanges(
  before: AmuletDecentralizedSynchronizerConfig | undefined,
  after: AmuletDecentralizedSynchronizerConfig | undefined
) {
  if (!before && !after) return [];

  const getRequiredSynchronizers = (synchronizers: DamlSet<string> | undefined) => {
    if (!synchronizers) return [];

    return synchronizers.map
      .entriesArray()
      .map(r => r[0])
      .sort();
  };

  const beforeRequiredSynchronizers = getRequiredSynchronizers(before?.requiredSynchronizers);
  const afterRequiredSynchronizers = getRequiredSynchronizers(after?.requiredSynchronizers);

  const allSynchronizers = [
    ...new Set([...beforeRequiredSynchronizers, ...afterRequiredSynchronizers]),
  ].sort();
  const requiredSynchronizersChanges = allSynchronizers.map((sync, idx) => ({
    fieldName: `decentralizedSynchronizerRequiredSynchronizers${idx + 1}`,
    label: `(unused) Decentralized synchronizer (Required synchronizer ${idx + 1})`,
    currentValue: beforeRequiredSynchronizers.includes(sync) ? sync : '',
    newValue: afterRequiredSynchronizers.includes(sync) ? sync : '',
  }));

  return [
    {
      fieldName: 'decentralizedSynchronizerActiveSynchronizer',
      label: 'The currently active synchronizer',
      currentValue: before?.activeSynchronizer || '',
      newValue: after?.activeSynchronizer || '',
      isId: true,
    },
    {
      fieldName: 'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstAmount',
      label: 'Traffic fees: Base rate: Burst amount limit',
      currentValue: before?.fees.baseRateTrafficLimits.burstAmount || '',
      newValue: after?.fees.baseRateTrafficLimits.burstAmount || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstWindow',
      label: 'Traffic fees: Base rate: Burst window limit (microseconds)',
      currentValue: before?.fees.baseRateTrafficLimits.burstWindow.microseconds || '',
      newValue: after?.fees.baseRateTrafficLimits.burstWindow.microseconds || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesExtraTrafficPrice',
      label: 'Traffic fees: Extra traffic: Price (in USD/MB)',
      currentValue: before?.fees.extraTrafficPrice || '',
      newValue: after?.fees.extraTrafficPrice || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesReadVsWriteScalingFactor',
      label: 'Traffic fees: Read/write scaling factor',
      currentValue: before?.fees.readVsWriteScalingFactor || '',
      newValue: after?.fees.readVsWriteScalingFactor || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesMinTopupAmount',
      label: 'Traffic fees: Minimum topup amount (in bytes)',
      currentValue: before?.fees.minTopupAmount || '',
      newValue: after?.fees.minTopupAmount || '',
    },
    ...requiredSynchronizersChanges,
  ] as ConfigChange[];
}
