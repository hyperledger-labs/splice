// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, test } from 'vitest';
import { AmuletConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { RecursivePartial } from './types';
import { buildAmuletConfigChanges } from '../../utils/buildAmuletConfigChanges';

describe('buildAmuletConfigChanges', () => {
  test('should mark decentralized synchronizer fields as disabled', () => {
    const mockConfig: RecursivePartial<AmuletConfig<'USD'>> = {
      tickDuration: { microseconds: '1000' },
      transferConfig: {
        createFee: { fee: '0.1' },
        holdingFee: { rate: '0.01' },
        transferFee: { initialRate: '0.01', steps: [] },
        lockHolderFee: { fee: '0.1' },
        extraFeaturedAppRewardAmount: '0.1',
        maxNumInputs: '10',
        maxNumOutputs: '10',
        maxNumLockHolders: '10',
      },
      decentralizedSynchronizer: {
        activeSynchronizer: 'sync1',
        requiredSynchronizers: {
          map: {
            entriesArray: () => [
              ['sync1', {}],
              ['sync2', {}],
            ],
          },
        },
        fees: {
          baseRateTrafficLimits: {
            burstAmount: '1000',
            burstWindow: { microseconds: '60000000' },
          },
          extraTrafficPrice: '0.1',
          readVsWriteScalingFactor: '1.5',
          minTopupAmount: '10',
        },
      },
    };

    const changes = buildAmuletConfigChanges(
      mockConfig as AmuletConfig<'USD'>,
      mockConfig as AmuletConfig<'USD'>,
      true
    );

    const disabledFieldPatterns = [
      /^decentralizedSynchronizerActiveSynchronizer$/,
      /^decentralizedSynchronizerRequiredSynchronizers\d+$/,
    ];

    changes.forEach(change => {
      if (disabledFieldPatterns.some(pattern => pattern.test(change.fieldName))) {
        expect(change.disabled).toBe(true);
      } else {
        expect(change.disabled).toBeFalsy();
      }
    });
  });

  test('renders the validator liveness reward cap label for the optValidatorFaucetCap field', () => {
    const mockConfig: RecursivePartial<AmuletConfig<'USD'>> = {
      tickDuration: { microseconds: '1000' },
      transferConfig: {
        createFee: { fee: '0.1' },
        holdingFee: { rate: '0.01' },
        transferFee: { initialRate: '0.01', steps: [] },
        lockHolderFee: { fee: '0.1' },
        extraFeaturedAppRewardAmount: '0.1',
        maxNumInputs: '10',
        maxNumOutputs: '10',
        maxNumLockHolders: '10',
      },
      issuanceCurve: {
        initialValue: { optValidatorFaucetCap: '2.85' },
        futureValues: [{ _1: { microseconds: '0' }, _2: { optValidatorFaucetCap: '3.00' } }],
      },
      decentralizedSynchronizer: {
        activeSynchronizer: 'sync1',
        requiredSynchronizers: {
          map: { entriesArray: () => [['sync1', {}]] },
        },
        fees: {
          baseRateTrafficLimits: {
            burstAmount: '1000',
            burstWindow: { microseconds: '60000000' },
          },
          extraTrafficPrice: '0.1',
          readVsWriteScalingFactor: '1.5',
          minTopupAmount: '10',
        },
      },
    };

    const changes = buildAmuletConfigChanges(
      mockConfig as AmuletConfig<'USD'>,
      mockConfig as AmuletConfig<'USD'>,
      true
    );

    const initialLabel = changes.find(
      c => c.fieldName === 'issuanceCurveInitialValueOptValidatorFaucetCap'
    )?.label;
    const futureLabel = changes.find(
      c => c.fieldName === 'issuanceCurveFutureValues0OptValidatorFaucetCap'
    )?.label;

    expect(initialLabel).toBe('Minting curve: Initial value: Validator liveness reward cap');
    expect(futureLabel).toBe('Minting curve: Step 0: Validator liveness reward cap');
  });
});
