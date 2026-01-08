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
      label: 'Tick Duration (microseconds)',
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
      label: 'Featured App Activity Marker Amount',
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
      label: 'Transfer (Create Fee)',
      currentValue: before?.transferConfig.createFee.fee || '',
      newValue: after?.transferConfig.createFee.fee || '',
    },
    {
      fieldName: 'transferConfigHoldingFeeRate',
      label: 'Transfer (Holding Fee Rate)',
      currentValue: before?.transferConfig.holdingFee?.rate || '',
      newValue: after?.transferConfig.holdingFee?.rate || '',
    },
    {
      fieldName: 'transferConfigTransferFeeInitialRate',
      label: 'Transfer Fee (Initial Rate)',
      currentValue: before?.transferConfig.transferFee?.initialRate || '',
      newValue: after?.transferConfig.transferFee?.initialRate || '',
    },

    ...buildTransferFeeStepsChanges(
      before?.transferConfig.transferFee?.steps,
      after?.transferConfig.transferFee?.steps
    ),

    {
      fieldName: 'transferConfigLockHolderFee',
      label: 'Lock Holder Fee',
      currentValue: before?.transferConfig.lockHolderFee.fee || '',
      newValue: after?.transferConfig.lockHolderFee.fee || '',
    },
    {
      fieldName: 'transferConfigExtraFeaturedAppRewardAmount',
      label: 'Extra Featured App Reward Amount',
      currentValue: before?.transferConfig.extraFeaturedAppRewardAmount || '',
      newValue: after?.transferConfig.extraFeaturedAppRewardAmount || '',
    },
    {
      fieldName: 'transferConfigMaxNumInputs',
      label: 'Max Num Inputs',
      currentValue: before?.transferConfig.maxNumInputs || '',
      newValue: after?.transferConfig.maxNumInputs || '',
    },
    {
      fieldName: 'transferConfigMaxNumOutputs',
      label: 'Max Num Outputs',
      currentValue: before?.transferConfig.maxNumOutputs || '',
      newValue: after?.transferConfig.maxNumOutputs || '',
    },
    {
      fieldName: 'transferConfigMaxNumLockHolders',
      label: 'Max Num Lock Holders',
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
      label: 'Package Config (Amulet)',
      currentValue: before?.amulet || '',
      newValue: after?.amulet || '',
    },
    {
      fieldName: 'packageConfigAmuletNameService',
      label: 'Package Config (Amulet Name Service)',
      currentValue: before?.amuletNameService || '',
      newValue: after?.amuletNameService || '',
    },
    {
      fieldName: 'packageConfigDsoGovernance',
      label: 'Package Config (DSO Governance)',
      currentValue: before?.dsoGovernance || '',
      newValue: after?.dsoGovernance || '',
    },
    {
      fieldName: 'packageConfigValidatorLifecycle',
      label: 'Package Config (Validator Lifecycle)',
      currentValue: before?.validatorLifecycle || '',
      newValue: after?.validatorLifecycle || '',
    },
    {
      fieldName: 'packageConfigWallet',
      label: 'Package Config (Wallet)',
      currentValue: before?.wallet || '',
      newValue: after?.wallet || '',
    },
    {
      fieldName: 'packageConfigWalletPayments',
      label: 'Package Config (Wallet Payments)',
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
      label: 'Issuance Curve Initial Value (Amulet to Issue Per Year)',
      currentValue: before?.initialValue?.amuletToIssuePerYear || '',
      newValue: after?.initialValue?.amuletToIssuePerYear || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueValidatorRewardPercentage',
      label: 'Issuance Curve Initial Value (Validator Reward Percentage)',
      currentValue: before?.initialValue?.validatorRewardPercentage || '',
      newValue: after?.initialValue?.validatorRewardPercentage || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueAppRewardPercentage',
      label: 'Issuance Curve Initial Value (App Reward Percentage)',
      currentValue: before?.initialValue?.appRewardPercentage || '',
      newValue: after?.initialValue?.appRewardPercentage || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueValidatorRewardCap',
      label: 'Issuance Curve Initial Value (Validator Reward Cap)',
      currentValue: before?.initialValue?.validatorRewardCap || '',
      newValue: after?.initialValue?.validatorRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueFeaturedAppRewardCap',
      label: 'Issuance Curve Initial Value (Featured App Reward Cap)',
      currentValue: before?.initialValue?.featuredAppRewardCap || '',
      newValue: after?.initialValue?.featuredAppRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueUnfeaturedAppRewardCap',
      label: 'Issuance Curve Initial Value (Unfeatured App Reward Cap)',
      currentValue: before?.initialValue?.unfeaturedAppRewardCap || '',
      newValue: after?.initialValue?.unfeaturedAppRewardCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueOptValidatorFaucetCap',
      label: 'Issuance Curve Initial Value (Validator Faucet Cap)',
      currentValue: before?.initialValue?.optValidatorFaucetCap || '',
      newValue: after?.initialValue?.optValidatorFaucetCap || '',
    },
    {
      fieldName: 'issuanceCurveInitialValueOptDevelopmentFundPercentage',
      label: 'Issuance Curve Initial Value (Development Fund Percentage)',
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
            label: `Issuance Curve Future Value (microseconds) (${idx})`,
            currentValue: fv._1.microseconds || '',
            newValue: after?.futureValues[idx]._1.microseconds || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}AmuletToIssuePerYear`,
            label: `Issuance Curve Future Value (Amulet to Issue Per Year) (${idx})`,
            currentValue: fv._2.amuletToIssuePerYear || '',
            newValue: after?.futureValues[idx]._2.amuletToIssuePerYear || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}ValidatorRewardPercentage`,
            label: `Issuance Curve Future Value (Validator Reward Percentage) (${idx})`,
            currentValue: fv._2.validatorRewardPercentage || '',
            newValue: after?.futureValues[idx]._2.validatorRewardPercentage || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}AppRewardPercentage`,
            label: `Issuance Curve Future Value (App Reward Percentage) (${idx})`,
            currentValue: fv._2.appRewardPercentage || '',
            newValue: after?.futureValues[idx]._2.appRewardPercentage || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}ValidatorRewardCap`,
            label: `Issuance Curve Future Value (Validator Reward Cap) (${idx})`,
            currentValue: fv._2.validatorRewardCap || '',
            newValue: after?.futureValues[idx]._2.validatorRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}FeaturedAppRewardCap`,
            label: `Issuance Curve Future Value (Featured App Reward Cap) (${idx})`,
            currentValue: fv._2.featuredAppRewardCap || '',
            newValue: after?.futureValues[idx]._2.featuredAppRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}UnfeaturedAppRewardCap`,
            label: `Issuance Curve Future Value (Unfeatured App Reward Cap) (${idx})`,
            currentValue: fv._2.unfeaturedAppRewardCap || '',
            newValue: after?.futureValues[idx]._2.unfeaturedAppRewardCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}OptValidatorFaucetCap`,
            label: `Issuance Curve Future Value (Validator Faucet Cap) (${idx})`,
            currentValue: fv._2.optValidatorFaucetCap || '',
            newValue: after?.futureValues[idx]._2.optValidatorFaucetCap || '',
          },
          {
            fieldName: `issuanceCurveFutureValues${idx}OptDevelopmentFundPercentage`,
            label: `Issuance Curve Future Value (Development Fund Percentage) (${idx})`,
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
    label: `Decentralized Synchronizer (Required Synchronizer ${idx + 1})`,
    currentValue: beforeRequiredSynchronizers.includes(sync) ? sync : '',
    newValue: afterRequiredSynchronizers.includes(sync) ? sync : '',
  }));

  return [
    {
      fieldName: 'decentralizedSynchronizerActiveSynchronizer',
      label: 'Decentralized Synchronizer (Active Synchronizer)',
      currentValue: before?.activeSynchronizer || '',
      newValue: after?.activeSynchronizer || '',
      isId: true,
    },
    {
      fieldName: 'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstAmount',
      label: 'Decentralized Synchronizer Fees (Base rate Traffic Limits Burst Amount)',
      currentValue: before?.fees.baseRateTrafficLimits.burstAmount || '',
      newValue: after?.fees.baseRateTrafficLimits.burstAmount || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstWindow',
      label: 'Decentralized Synchronizer Fees (Base rate Traffic Limits Burst Window)',
      currentValue: before?.fees.baseRateTrafficLimits.burstWindow.microseconds || '',
      newValue: after?.fees.baseRateTrafficLimits.burstWindow.microseconds || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesExtraTrafficPrice',
      label: 'Decentralized Synchronizer Fees (Extra Traffic Price)',
      currentValue: before?.fees.extraTrafficPrice || '',
      newValue: after?.fees.extraTrafficPrice || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesReadVsWriteScalingFactor',
      label: 'Decentralized Synchronizer Fees (Read/Write Scaling Factor)',
      currentValue: before?.fees.readVsWriteScalingFactor || '',
      newValue: after?.fees.readVsWriteScalingFactor || '',
    },
    {
      fieldName: 'decentralizedSynchronizerFeesMinTopupAmount',
      label: 'Decentralized Synchronizer Fees (Min Topup Amount)',
      currentValue: before?.fees.minTopupAmount || '',
      newValue: after?.fees.minTopupAmount || '',
    },
    ...requiredSynchronizersChanges,
  ] as ConfigChange[];
}
