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
      label: 'Round Tick Duration (microseconds)',
      currentValue: before?.tickDuration.microseconds || '',
      newValue: after?.tickDuration.microseconds || '',
    },
    {
      fieldName: 'transferPreapprovalFee',
      label: 'Transfer Preapproval Fee',
      currentValue: before?.transferPreapprovalFee || '',
      newValue: after?.transferPreapprovalFee || '',
    },
    {
      fieldName: 'featuredAppActivityMarkerAmount',
      label: 'Amount of the AppRewardCoupon Contract that a FeaturedAppActivityMarker is Converted To',
      currentValue: before?.featuredAppActivityMarkerAmount || '',
      newValue: after?.featuredAppActivityMarkerAmount || '',
    },
    {
      fieldName: 'optDevelopmentFundManager',
      label: 'Development Fund Manager',
      currentValue: before?.optDevelopmentFundManager || '',
      newValue: after?.optDevelopmentFundManager || '',
    },
    {
      fieldName: 'transferConfigCreateFee',
      label: 'Fee Per Created Output Contract In A Transfer',
      currentValue: before?.transferConfig.createFee.fee || '',
      newValue: after?.transferConfig.createFee.fee || '',
    },
    {
      fieldName: 'transferConfigHoldingFeeRate',
      label: 'Holding Fee Per Round',
      currentValue: before?.transferConfig.holdingFee?.rate || '',
      newValue: after?.transferConfig.holdingFee?.rate || '',
    },
    {
      fieldName: 'transferConfigTransferFeeInitialRate',
      label: 'Basic Transfer Fee',
      currentValue: before?.transferConfig.transferFee?.initialRate || '',
      newValue: after?.transferConfig.transferFee?.initialRate || '',
    },

    ...buildTransferFeeStepsChanges(
      before?.transferConfig.transferFee?.steps,
      after?.transferConfig.transferFee?.steps
    ),

    {
      fieldName: 'transferConfigLockHolderFee',
      label: 'Fee Per Lock Holder Output In A Transfer',
      currentValue: before?.transferConfig.lockHolderFee.fee || '',
      newValue: after?.transferConfig.lockHolderFee.fee || '',
    },
    {
      fieldName: 'transferConfigExtraFeaturedAppRewardAmount',
      label: 'Extra Amount Of Reward For Featured Apps, In USD',
      currentValue: before?.transferConfig.extraFeaturedAppRewardAmount || '',
      newValue: after?.transferConfig.extraFeaturedAppRewardAmount || '',
    },
    {
      fieldName: 'transferConfigMaxNumInputs',
      label: 'Max Number Of Input Contracts In A Transfer',
      currentValue: before?.transferConfig.maxNumInputs || '',
      newValue: after?.transferConfig.maxNumInputs || '',
    },
    {
      fieldName: 'transferConfigMaxNumOutputs',
      label: 'Max Number Of Outputs In A Transfer',
      currentValue: before?.transferConfig.maxNumOutputs || '',
      newValue: after?.transferConfig.maxNumOutputs || '',
    },
    {
      fieldName: 'transferConfigMaxNumLockHolders',
      label: 'Max Number Of Lock Holders In A Transfer',
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
      label: 'Daml Model Version: amulet',
      currentValue: before?.amulet || '',
      newValue: after?.amulet || '',
    },
    {
      fieldName: 'packageConfigAmuletNameService',
      label: 'Daml Model Version: amuletNameService',
      currentValue: before?.amuletNameService || '',
      newValue: after?.amuletNameService || '',
    },
    {
      fieldName: 'packageConfigDsoGovernance',
      label: 'Daml Model Version: dsoGovernance',
      currentValue: before?.dsoGovernance || '',
      newValue: after?.dsoGovernance || '',
    },
    {
      fieldName: 'packageConfigValidatorLifecycle',
      label: 'Daml Model Version: validatorLifecycle',
      currentValue: before?.validatorLifecycle || '',
      newValue: after?.validatorLifecycle || '',
    },
    {
      fieldName: 'packageConfigWallet',
      label: 'Daml Model Version: wallet',
      currentValue: before?.wallet || '',
      newValue: after?.wallet || '',
    },
    {
      fieldName: 'packageConfigWalletPayments',
      label: 'Daml Model Version: walletPayments',
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
            label: `Transfer Fee Step ${idx}`,
            currentValue: b._1,
            newValue: a?._1,
          },
          {
            fieldName: `transferFeeSteps${idx}_2`,
            label: `Transfer Fee Step ${idx}`,
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
      label: 'Minting Curve: Initial Value: Amulet to Issue Per Year',
      currentValue: before?.initialValue?.amuletToIssuePerYear || '',
      newValue: after?.initialValue?.amuletToIssuePerYear || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueValidatorRewardPercentage',
      label: 'Minting Curve: Initial Value: Validator Reward Percentage',
      currentValue: before?.initialValue?.validatorRewardPercentage || '',
      newValue: after?.initialValue?.validatorRewardPercentage || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueAppRewardPercentage',
      label: 'Minting Curve: Initial Value: App Reward Percentage',
      currentValue: before?.initialValue?.appRewardPercentage || '',
      newValue: after?.initialValue?.appRewardPercentage || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueValidatorRewardCap',
      label: 'Minting Curve: Initial Value: Validator Reward Cap',
      currentValue: before?.initialValue?.validatorRewardCap || '',
      newValue: after?.initialValue?.validatorRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueFeaturedAppRewardCap',
      label: 'Minting Curve: Initial Value: Featured App Reward Cap',
      currentValue: before?.initialValue?.featuredAppRewardCap || '',
      newValue: after?.initialValue?.featuredAppRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueUnfeaturedAppRewardCap',
      label: 'Minting Curve: Initial Value: Unfeatured App Reward Cap',
      currentValue: before?.initialValue?.unfeaturedAppRewardCap || '',
      newValue: after?.initialValue?.unfeaturedAppRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueOptValidatorFaucetCap',
      label: 'Minting Curve: Initial Value: Validator Faucet Cap',
      currentValue: before?.initialValue?.optValidatorFaucetCap || '',
      newValue: after?.initialValue?.optValidatorFaucetCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueOptDevelopmentFundPercentage',
      label: 'Minting Curve: Initial Value: Development Fund Percentage',
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
            label: `Minting Curve: Step ${idx}: Start At Time (microseconds)`,
            currentValue: fv._1.microseconds || '',
            newValue: after?.futureValues[idx]._1.microseconds || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}AmuletToIssuePerYear`,
            label: `Minting Curve: Step ${idx}: Amulet to Issue Per Year`,
            currentValue: fv._2.amuletToIssuePerYear || '',
            newValue: after?.futureValues[idx]._2.amuletToIssuePerYear || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}ValidatorRewardPercentage`,
            label: `Minting Curve: Step ${idx}: Validator Reward Percentage`,
            currentValue: fv._2.validatorRewardPercentage || '',
            newValue: after?.futureValues[idx]._2.validatorRewardPercentage || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}AppRewardPercentage`,
            label: `Minting Curve: Step ${idx}: App Reward Percentage`,
            currentValue: fv._2.appRewardPercentage || '',
            newValue: after?.futureValues[idx]._2.appRewardPercentage || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}ValidatorRewardCap`,
            label: `Minting Curve: Step ${idx}: Validator Reward Cap`,
            currentValue: fv._2.validatorRewardCap || '',
            newValue: after?.futureValues[idx]._2.validatorRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}FeaturedAppRewardCap`,
            label: `Minting Curve: Step ${idx}: Featured App Reward Cap`,
            currentValue: fv._2.featuredAppRewardCap || '',
            newValue: after?.futureValues[idx]._2.featuredAppRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}UnfeaturedAppRewardCap`,
            label: `Minting Curve: Step ${idx}: Unfeatured App Reward Cap`,
            currentValue: fv._2.unfeaturedAppRewardCap || '',
            newValue: after?.futureValues[idx]._2.unfeaturedAppRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}OptValidatorFaucetCap`,
            label: `Minting Curve: Step ${idx}: Validator Faucet Cap`,
            currentValue: fv._2.optValidatorFaucetCap || '',
            newValue: after?.futureValues[idx]._2.optValidatorFaucetCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}OptDevelopmentFundPercentage`,
            label: `Minting Curve: Step ${idx}: Development Fund Percentage`,
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
    label: `All Synchronizers That Amulet and ANS Users Should Be Connected To (index ${idx + 1})`,
    currentValue: beforeRequiredSynchronizers.includes(sync) ? sync : '',
    newValue: afterRequiredSynchronizers.includes(sync) ? sync : '',
  }));

  return [
    {
      fieldName: 'decentralizedSynchronizerActiveSynchronizer',
      label: 'The Currently Active Synchronizer (must be one of the required synchronizers)',
      currentValue: before?.activeSynchronizer || '',
      newValue: after?.activeSynchronizer || '',
      isId: true,
    },
    {
      fieldName: 'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstAmount',
      label: 'Traffic Fees: Base Rate: Burst Amount Limit',
      currentValue: before?.fees.baseRateTrafficLimits.burstAmount || '',
      newValue: after?.fees.baseRateTrafficLimits.burstAmount || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstWindow',
      label: 'Traffic Fees: Base Rate: Burst Window Limit (microseconds)',
      currentValue: before?.fees.baseRateTrafficLimits.burstWindow.microseconds || '',
      newValue: after?.fees.baseRateTrafficLimits.burstWindow.microseconds || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesExtraTrafficPrice',
      label: 'Traffic Fees: Extra Traffic: Price (in USD/MB)',
      currentValue: before?.fees.extraTrafficPrice || '',
      newValue: after?.fees.extraTrafficPrice || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesReadVsWriteScalingFactor',
      label: 'Traffic Fees: Read/Write Scaling Factor',
      currentValue: before?.fees.readVsWriteScalingFactor || '',
      newValue: after?.fees.readVsWriteScalingFactor || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesMinTopupAmount',
      label: 'Traffic Fees: Minimum Topup Amount (in bytes)',
      currentValue: before?.fees.minTopupAmount || '',
      newValue: after?.fees.minTopupAmount || '',
    },
    ...requiredSynchronizersChanges,
  ] as ConfigChange[];
}
