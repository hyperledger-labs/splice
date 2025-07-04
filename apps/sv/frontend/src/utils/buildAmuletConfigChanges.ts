// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Optional } from '@daml/types';
import { AmuletConfig, PackageConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { Tuple2 } from '@daml.js/daml-prim-DA-Types-1.0.0/lib/DA/Types';
import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types';
import { IssuanceConfig } from '@daml.js/splice-amulet/lib/Splice/Issuance';
import { Schedule } from '@daml.js/splice-amulet/lib/Splice/Schedule';
import { AmuletDecentralizedSynchronizerConfig } from '@daml.js/splice-amulet/lib/Splice/DecentralizedSynchronizer';
import { ConfigChange } from './types';

export function buildAmuletConfigChanges(
  before: Optional<AmuletConfig<'USD'>>,
  after: Optional<AmuletConfig<'USD'>>
): ConfigChange[] {
  const changes = [
    {
      fieldName: 'Tick Duration (microseconds)',
      currentValue: before?.tickDuration.microseconds || '',
      newValue: after?.tickDuration.microseconds || '',
    },
    {
      fieldName: 'Transfer Preapproval Fee',
      currentValue: before?.transferPreapprovalFee || '',
      newValue: after?.transferPreapprovalFee || '',
    },
    {
      fieldName: 'Featured App Activity Marker Amount',
      currentValue: before?.featuredAppActivityMarkerAmount || '',
      newValue: after?.featuredAppActivityMarkerAmount || '',
    },

    {
      fieldName: 'Transfer (Create Fee)',
      currentValue: before?.transferConfig.createFee.fee || '',
      newValue: after?.transferConfig.createFee.fee || '',
    },

    {
      fieldName: 'Transfer (Holding Fee Rate)',
      currentValue: before?.transferConfig.holdingFee?.rate || '',
      newValue: after?.transferConfig.holdingFee?.rate || '',
    },

    {
      fieldName: 'Transfer Fee (Initial Rate)',
      currentValue: before?.transferConfig.transferFee?.initialRate || '',
      newValue: after?.transferConfig.transferFee?.initialRate || '',
    },

    ...buildTransferFeeStepsChanges(
      before?.transferConfig.transferFee?.steps,
      after?.transferConfig.transferFee?.steps
    ),

    {
      fieldName: 'Lock Holder Fee',
      currentValue: before?.transferConfig.lockHolderFee.fee || '',
      newValue: after?.transferConfig.lockHolderFee.fee || '',
    },
    {
      fieldName: 'Extra Featured App Reward Amount',
      currentValue: before?.transferConfig.extraFeaturedAppRewardAmount || '',
      newValue: after?.transferConfig.extraFeaturedAppRewardAmount || '',
    },
    {
      fieldName: 'Max Num Inputs',
      currentValue: before?.transferConfig.maxNumInputs || '',
      newValue: after?.transferConfig.maxNumInputs || '',
    },
    {
      fieldName: 'Max Num Outputs',
      currentValue: before?.transferConfig.maxNumOutputs || '',
      newValue: after?.transferConfig.maxNumOutputs || '',
    },
    {
      fieldName: 'Max Num Lock Holders',
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

  return changes.filter(c => c.currentValue !== c.newValue);
}

function buildPackageConfigChanges(
  before: PackageConfig | undefined,
  after: PackageConfig | undefined
) {
  if (!before && !after) return [];
  return [
    {
      fieldName: 'Package Config (Amulet)',
      currentValue: before?.amulet || '',
      newValue: after?.amulet || '',
    },
    {
      fieldName: 'Package Config (Amulet Name Service)',
      currentValue: before?.amuletNameService || '',
      newValue: after?.amuletNameService || '',
    },
    {
      fieldName: 'Package Config (DSO Governance)',
      currentValue: before?.dsoGovernance || '',
      newValue: after?.dsoGovernance || '',
    },
    {
      fieldName: 'Package Config (Validator Lifecycle)',
      currentValue: before?.validatorLifecycle || '',
      newValue: after?.validatorLifecycle || '',
    },
    {
      fieldName: 'Package Config (Wallet)',
      currentValue: before?.wallet || '',
      newValue: after?.wallet || '',
    },
    {
      fieldName: 'Package Config (Wallet Payments)',
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
      ?.map((b, idx) => {
        const a = after?.[idx];
        return [
          {
            fieldName: `Transfer Fee Step ${idx}`,
            currentValue: b._1,
            newValue: a?._1,
          },
          {
            fieldName: `Transfer Fee Step ${idx}`,
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
      fieldName: 'Issuance Curve Initial Value (Amulet to Issue Per Year)',
      currentValue: before?.initialValue?.amuletToIssuePerYear || '',
      newValue: after?.initialValue?.amuletToIssuePerYear || '',
    },
    {
      fieldName: 'Issuance Curve Initial Value (Validator Reward Percentage)',
      currentValue: before?.initialValue?.validatorRewardPercentage || '',
      newValue: after?.initialValue?.validatorRewardPercentage || '',
    },
    {
      fieldName: 'Issuance Curve Initial Value (App Reward Percentage)',
      currentValue: before?.initialValue?.appRewardPercentage || '',
      newValue: after?.initialValue?.appRewardPercentage || '',
    },
    {
      fieldName: 'Issuance Curve Initial Value (Validator Reward Cap)',
      currentValue: before?.initialValue?.validatorRewardCap || '',
      newValue: after?.initialValue?.validatorRewardCap || '',
    },
    {
      fieldName: 'Issuance Curve Initial Value (Featured App Reward Cap)',
      currentValue: before?.initialValue?.featuredAppRewardCap || '',
      newValue: after?.initialValue?.featuredAppRewardCap || '',
    },
    {
      fieldName: 'Issuance Curve Initial Value (Unfeatured App Reward Cap)',
      currentValue: before?.initialValue?.unfeaturedAppRewardCap || '',
      newValue: after?.initialValue?.unfeaturedAppRewardCap || '',
    },
    {
      fieldName: 'Issuance Curve Initial Value (Validator Faucet Cap)',
      currentValue: before?.initialValue?.optValidatorFaucetCap || '',
      newValue: after?.initialValue?.optValidatorFaucetCap || '',
    },
  ] as ConfigChange[];

  const futureValues =
    before?.futureValues
      .map((fv, idx) => {
        return [
          {
            fieldName: `Issuance Curve Future Value (microseconds) (${idx})`,
            currentValue: fv._1.microseconds || '',
            newValue: after?.futureValues[idx]._1.microseconds || '',
          },
          {
            fieldName: `Issuance Curve Future Value (Amulet to Issue Per Year) (${idx})`,
            currentValue: fv._2.amuletToIssuePerYear || '',
            newValue: after?.futureValues[idx]._2.amuletToIssuePerYear || '',
          },
          {
            fieldName: `Issuance Curve Future Value (Validator Reward Percentage) (${idx})`,
            currentValue: fv._2.validatorRewardPercentage || '',
            newValue: after?.futureValues[idx]._2.validatorRewardPercentage || '',
          },
          {
            fieldName: `Issuance Curve Future Value (App Reward Percentage) (${idx})`,
            currentValue: fv._2.appRewardPercentage || '',
            newValue: after?.futureValues[idx]._2.appRewardPercentage || '',
          },
          {
            fieldName: `Issuance Curve Future Value (Validator Reward Cap) (${idx})`,
            currentValue: fv._2.validatorRewardCap || '',
            newValue: after?.futureValues[idx]._2.validatorRewardCap || '',
          },
          {
            fieldName: `Issuance Curve Future Value (Featured App Reward Cap) (${idx})`,
            currentValue: fv._2.featuredAppRewardCap || '',
            newValue: after?.futureValues[idx]._2.featuredAppRewardCap || '',
          },
          {
            fieldName: `Issuance Curve Future Value (Unfeatured App Reward Cap) (${idx})`,
            currentValue: fv._2.unfeaturedAppRewardCap || '',
            newValue: after?.futureValues[idx]._2.unfeaturedAppRewardCap || '',
          },
          {
            fieldName: `Issuance Curve Future Value (Validator Faucet Cap) (${idx})`,
            currentValue: fv._2.optValidatorFaucetCap || '',
            newValue: after?.futureValues[idx]._2.optValidatorFaucetCap || '',
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

  return [
    {
      fieldName: 'Decentralized Synchronizer (Active Synchronizer)',
      currentValue: before?.activeSynchronizer || '',
      newValue: after?.activeSynchronizer || '',
      isId: true,
    },
    {
      fieldName: 'Decentralized Synchronizer Fees (Base rate Traffic Limits Burst Amount)',
      currentValue: before?.fees.baseRateTrafficLimits.burstAmount || '',
      newValue: after?.fees.baseRateTrafficLimits.burstAmount || '',
    },
    {
      fieldName: 'Decentralized Synchronizer Fees (Base rate Traffic Limits Burst Window)',
      currentValue: before?.fees.baseRateTrafficLimits.burstWindow.microseconds || '',
      newValue: after?.fees.baseRateTrafficLimits.burstWindow.microseconds || '',
    },
    {
      fieldName: 'Decentralized Synchronizer Fees (Extra Traffic Price)',
      currentValue: before?.fees.extraTrafficPrice || '',
      newValue: after?.fees.extraTrafficPrice || '',
    },
    {
      fieldName: 'Decentralized Synchronizer Fees (Read/Write Scaling Factor)',
      currentValue: before?.fees.readVsWriteScalingFactor || '',
      newValue: after?.fees.readVsWriteScalingFactor || '',
    },
    {
      fieldName: 'Decentralized Synchronizer Fees (Min Topup Amount)',
      currentValue: before?.fees.minTopupAmount || '',
      newValue: after?.fees.minTopupAmount || '',
    },
  ] as ConfigChange[];
}
