// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, test } from 'vitest';
import { ConfigChange } from '../../utils/types';
import { buildAmuletRulesConfigFromChanges } from '../../utils/buildAmuletRulesConfigFromChanges';

describe('buildAmuletRulesConfigFromChanges', () => {
  test('should build AmuletConfig with provided changes', () => {
    const changes: ConfigChange[] = [
      {
        fieldName: 'tickDuration',
        label: 'Tick Duration',
        currentValue: '1000',
        newValue: '2000',
      },
      {
        fieldName: 'transferPreapprovalFee',
        label: 'Transfer Preapproval Fee',
        currentValue: '0.1',
        newValue: '0.2',
      },
      {
        fieldName: 'featuredAppActivityMarkerAmount',
        label: 'Featured App Activity Marker Amount',
        currentValue: '100',
        newValue: '200',
      },
      {
        fieldName: 'transferConfigCreateFee',
        label: 'Transfer Config Create Fee',
        currentValue: '0.5',
        newValue: '1.0',
      },
      {
        fieldName: 'transferConfigHoldingFeeRate',
        label: 'Transfer Config Holding Fee Rate',
        currentValue: '0.01',
        newValue: '0.02',
      },
      {
        fieldName: 'transferConfigTransferFeeInitialRate',
        label: 'Transfer Fee Initial Rate',
        currentValue: '0.001',
        newValue: '0.002',
      },
      {
        fieldName: 'transferFeeSteps1_1',
        label: 'Transfer Fee Step 1 Amount',
        currentValue: '100',
        newValue: '200',
      },
      {
        fieldName: 'transferFeeSteps1_2',
        label: 'Transfer Fee Step 1 Rate',
        currentValue: '0.001',
        newValue: '0.002',
      },
      {
        fieldName: 'transferConfigLockHolderFee',
        label: 'Lock Holder Fee',
        currentValue: '0.1',
        newValue: '0.2',
      },
      {
        fieldName: 'transferConfigExtraFeaturedAppRewardAmount',
        label: 'Extra Featured App Reward Amount',
        currentValue: '50',
        newValue: '100',
      },
      {
        fieldName: 'transferConfigMaxNumInputs',
        label: 'Max Num Inputs',
        currentValue: '10',
        newValue: '20',
      },
      {
        fieldName: 'transferConfigMaxNumOutputs',
        label: 'Max Num Outputs',
        currentValue: '10',
        newValue: '20',
      },
      {
        fieldName: 'transferConfigMaxNumLockHolders',
        label: 'Max Num Lock Holders',
        currentValue: '5',
        newValue: '10',
      },
      {
        fieldName: 'issuanceCurveInitialValueAmuletToIssuePerYear',
        label: 'Amulet To Issue Per Year',
        currentValue: '1000000',
        newValue: '2000000',
      },
      {
        fieldName: 'issuanceCurveInitialValueValidatorRewardPercentage',
        label: 'Validator Reward Percentage',
        currentValue: '0.4',
        newValue: '0.5',
      },
      {
        fieldName: 'issuanceCurveInitialValueAppRewardPercentage',
        label: 'App Reward Percentage',
        currentValue: '0.3',
        newValue: '0.35',
      },
      {
        fieldName: 'issuanceCurveInitialValueValidatorRewardCap',
        label: 'Validator Reward Cap',
        currentValue: '1000',
        newValue: '2000',
      },
      {
        fieldName: 'issuanceCurveInitialValueFeaturedAppRewardCap',
        label: 'Featured App Reward Cap',
        currentValue: '500',
        newValue: '1000',
      },
      {
        fieldName: 'issuanceCurveInitialValueUnfeaturedAppRewardCap',
        label: 'Unfeatured App Reward Cap',
        currentValue: '100',
        newValue: '200',
      },
      {
        fieldName: 'issuanceCurveInitialValueOptValidatorFaucetCap',
        label: 'Opt Validator Faucet Cap',
        currentValue: '50',
        newValue: '100',
      },
      {
        fieldName: 'decentralizedSynchronizerActiveSynchronizer',
        label: 'Active Synchronizer',
        currentValue: 'sync1',
        newValue: 'sync2',
      },
      {
        fieldName: 'decentralizedSynchronizerRequiredSynchronizers1',
        label: 'Required Synchronizer 1',
        currentValue: 'sync1',
        newValue: 'sync1',
      },
      {
        fieldName: 'decentralizedSynchronizerRequiredSynchronizers2',
        label: 'Required Synchronizer 2',
        currentValue: 'sync2',
        newValue: 'sync2',
      },
      {
        fieldName: 'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstAmount',
        label: 'Burst Amount',
        currentValue: '1000',
        newValue: '2000',
      },
      {
        fieldName: 'decentralizedSynchronizerFeesBaseRateTrafficLimitsBurstWindow',
        label: 'Burst Window',
        currentValue: '60000000',
        newValue: '120000000',
      },
      {
        fieldName: 'decentralizedSynchronizerFeesExtraTrafficPrice',
        label: 'Extra Traffic Price',
        currentValue: '0.1',
        newValue: '0.2',
      },
      {
        fieldName: 'decentralizedSynchronizerFeesReadVsWriteScalingFactor',
        label: 'Read Vs Write Scaling Factor',
        currentValue: '1.5',
        newValue: '2.0',
      },
      {
        fieldName: 'decentralizedSynchronizerFeesMinTopupAmount',
        label: 'Min Topup Amount',
        currentValue: '10',
        newValue: '20',
      },
      {
        fieldName: 'packageConfigAmulet',
        label: 'Amulet Package',
        currentValue: '0.1.1',
        newValue: '0.2.0',
      },
      {
        fieldName: 'packageConfigAmuletNameService',
        label: 'Amulet Name Service Package',
        currentValue: '0.1.1',
        newValue: '0.2.0',
      },
      {
        fieldName: 'packageConfigDsoGovernance',
        label: 'DSO Governance Package',
        currentValue: '0.1.1',
        newValue: '0.2.0',
      },
      {
        fieldName: 'packageConfigValidatorLifecycle',
        label: 'Validator Lifecycle Package',
        currentValue: '0.1.1',
        newValue: '0.2.0',
      },
      {
        fieldName: 'packageConfigWallet',
        label: 'Wallet Package',
        currentValue: '0.1.1',
        newValue: '0.2.0',
      },
      {
        fieldName: 'packageConfigWalletPayments',
        label: 'Wallet Payments Package',
        currentValue: '0.1.1',
        newValue: '0.2.0',
      },
    ];

    const result = buildAmuletRulesConfigFromChanges(changes);

    expect(result.tickDuration.microseconds).toBe('2000');
    expect(result.transferPreapprovalFee).toBe('0.2');
    expect(result.featuredAppActivityMarkerAmount).toBe('200');

    expect(result.transferConfig.createFee.fee).toBe('1.0');
    expect(result.transferConfig.holdingFee).toEqual({ rate: '0.02' });
    expect(result.transferConfig.transferFee.initialRate).toBe('0.002');
    expect(result.transferConfig.transferFee.steps).toEqual([{ _1: '200', _2: '0.002' }]);
    expect(result.transferConfig.lockHolderFee.fee).toBe('0.2');
    expect(result.transferConfig.extraFeaturedAppRewardAmount).toBe('100');
    expect(result.transferConfig.maxNumInputs).toBe('20');
    expect(result.transferConfig.maxNumOutputs).toBe('20');
    expect(result.transferConfig.maxNumLockHolders).toBe('10');

    expect(result.issuanceCurve.initialValue.amuletToIssuePerYear).toBe('2000000');
    expect(result.issuanceCurve.initialValue.validatorRewardPercentage).toBe('0.5');
    expect(result.issuanceCurve.initialValue.appRewardPercentage).toBe('0.35');
    expect(result.issuanceCurve.initialValue.validatorRewardCap).toBe('2000');
    expect(result.issuanceCurve.initialValue.featuredAppRewardCap).toBe('1000');
    expect(result.issuanceCurve.initialValue.unfeaturedAppRewardCap).toBe('200');
    expect(result.issuanceCurve.initialValue.optValidatorFaucetCap).toBe('100');

    expect(result.decentralizedSynchronizer.activeSynchronizer).toBe('sync2');
    const expectedRequiredSynchronizers = Array.from(
      result.decentralizedSynchronizer.requiredSynchronizers.map.entriesArray().map(e => e[0])
    ).sort();
    expect(expectedRequiredSynchronizers).toEqual(['sync1', 'sync2']);
    expect(result.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstAmount).toBe('2000');
    expect(
      result.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstWindow.microseconds
    ).toBe('120000000');
    expect(result.decentralizedSynchronizer.fees.extraTrafficPrice).toBe('0.2');
    expect(result.decentralizedSynchronizer.fees.readVsWriteScalingFactor).toBe('2.0');
    expect(result.decentralizedSynchronizer.fees.minTopupAmount).toBe('20');

    expect(result.packageConfig.amulet).toBe('0.2.0');
    expect(result.packageConfig.amuletNameService).toBe('0.2.0');
    expect(result.packageConfig.dsoGovernance).toBe('0.2.0');
    expect(result.packageConfig.validatorLifecycle).toBe('0.2.0');
    expect(result.packageConfig.wallet).toBe('0.2.0');
    expect(result.packageConfig.walletPayments).toBe('0.2.0');
  });

  test('should handle multiple transfer fee steps', () => {
    const changes: ConfigChange[] = [
      {
        fieldName: 'transferConfigTransferFeeInitialRate',
        label: 'Transfer Fee Initial Rate',
        currentValue: '0.001',
        newValue: '0.002',
      },
      {
        fieldName: 'transferFeeSteps1_1',
        label: 'Transfer Fee Step 1 Amount',
        currentValue: '100',
        newValue: '200',
      },
      {
        fieldName: 'transferFeeSteps1_2',
        label: 'Transfer Fee Step 1 Rate',
        currentValue: '0.001',
        newValue: '0.002',
      },
      {
        fieldName: 'transferFeeSteps2_1',
        label: 'Transfer Fee Step 2 Amount',
        currentValue: '500',
        newValue: '1000',
      },
      {
        fieldName: 'transferFeeSteps2_2',
        label: 'Transfer Fee Step 2 Rate',
        currentValue: '0.0005',
        newValue: '0.001',
      },
    ];

    const result = buildAmuletRulesConfigFromChanges(changes);

    expect(result.transferConfig.transferFee.steps).toEqual([
      { _1: '200', _2: '0.002' },
      { _1: '1000', _2: '0.001' },
    ]);
  });

  test('should handle issuance curve future values', () => {
    const changes: ConfigChange[] = [
      {
        fieldName: 'issuanceCurveFutureValues0',
        label: 'Future Value 0 Time',
        currentValue: '1000000',
        newValue: '2000000',
      },
      {
        fieldName: 'issuanceCurveFutureValues0AmuletToIssuePerYear',
        label: 'Future Value 0 Amulet To Issue Per Year',
        currentValue: '1000000',
        newValue: '2000000',
      },
      {
        fieldName: 'issuanceCurveFutureValues0ValidatorRewardPercentage',
        label: 'Future Value 0 Validator Reward Percentage',
        currentValue: '0.4',
        newValue: '0.5',
      },
      {
        fieldName: 'issuanceCurveFutureValues0AppRewardPercentage',
        label: 'Future Value 0 App Reward Percentage',
        currentValue: '0.3',
        newValue: '0.35',
      },
      {
        fieldName: 'issuanceCurveFutureValues0ValidatorRewardCap',
        label: 'Future Value 0 Validator Reward Cap',
        currentValue: '1000',
        newValue: '2000',
      },
      {
        fieldName: 'issuanceCurveFutureValues0FeaturedAppRewardCap',
        label: 'Future Value 0 Featured App Reward Cap',
        currentValue: '500',
        newValue: '1000',
      },
      {
        fieldName: 'issuanceCurveFutureValues0UnfeaturedAppRewardCap',
        label: 'Future Value 0 Unfeatured App Reward Cap',
        currentValue: '100',
        newValue: '200',
      },
      {
        fieldName: 'issuanceCurveFutureValues0OptValidatorFaucetCap',
        label: 'Future Value 0 Opt Validator Faucet Cap',
        currentValue: '50',
        newValue: '100',
      },
    ];

    const result = buildAmuletRulesConfigFromChanges(changes);

    expect(result.issuanceCurve.futureValues.length).toBe(1);
    expect(result.issuanceCurve.futureValues[0]).toEqual({
      _1: { microseconds: '2000000' },
      _2: {
        amuletToIssuePerYear: '2000000',
        validatorRewardPercentage: '0.5',
        appRewardPercentage: '0.35',
        validatorRewardCap: '2000',
        featuredAppRewardCap: '1000',
        unfeaturedAppRewardCap: '200',
        optValidatorFaucetCap: '100',
      },
    });
  });
});
