// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  getAmuletSetConfigAction,
  getDsoSetConfigAction,
  getDsoSvOffboardingAction,
  getUpdateSvRewardWeightAction,
  mkVoteRequest,
} from '@lfdecentralizedtrust/splice-common-test-handlers';
import { test, expect, describe } from 'vitest';

import { hasConflictingFields } from '../utils/configDiffs';
import { formatBasisPoints } from '../utils/governance';

describe('Utility', () => {
  test('hasConflictingFields function works as expected for DsoRulesConfig changes', async () => {
    const configWithNoChange = getDsoSetConfigAction(
      { new: '4', base: '4' },
      { new: '4', base: '4' }
    );
    const configWithNumMemberTrafficContractsThresholdChange = getDsoSetConfigAction(
      { new: '4', base: '4' },
      { new: '8', base: '4' }
    );
    const configWithAcsCommitmentReconciliationInterval = getDsoSetConfigAction(
      { new: '8', base: '4' },
      { new: '4', base: '4' }
    );
    const configWithTwoChanges = getDsoSetConfigAction(
      { new: '15', base: '4' },
      { new: '16', base: '4' }
    );
    const configWithNumMemberTrafficContractsThresholdChangeOldAction = getDsoSetConfigAction(
      { new: '4' },
      { new: '23' }
    );

    const noChanges1 = hasConflictingFields(configWithNoChange, []);
    expect(noChanges1).toStrictEqual({ hasConflict: false, intersection: [] });
    const noChanges2 = hasConflictingFields(configWithNoChange, [
      mkVoteRequest(configWithNoChange),
    ]);
    expect(noChanges2).toStrictEqual({ hasConflict: false, intersection: [] });

    const overlapChanges1 = hasConflictingFields(
      configWithNumMemberTrafficContractsThresholdChange,
      [mkVoteRequest(configWithNoChange)]
    );
    expect(overlapChanges1).toStrictEqual({ hasConflict: false, intersection: [] });
    const overlapChanges2 = hasConflictingFields(
      configWithNumMemberTrafficContractsThresholdChange,
      [mkVoteRequest(configWithAcsCommitmentReconciliationInterval)]
    );
    expect(overlapChanges2).toStrictEqual({ hasConflict: false, intersection: [] });
    const overlapChanges3 = hasConflictingFields(configWithTwoChanges, [
      mkVoteRequest(configWithNoChange),
    ]);
    expect(overlapChanges3).toStrictEqual({ hasConflict: false, intersection: [] });

    const intersectChanges1 = hasConflictingFields(
      configWithNumMemberTrafficContractsThresholdChange,
      [mkVoteRequest(configWithNumMemberTrafficContractsThresholdChange)]
    );
    expect(intersectChanges1).toStrictEqual({
      hasConflict: true,
      intersection: ['numMemberTrafficContractsThreshold'],
    });
    const intersectChanges2 = hasConflictingFields(configWithAcsCommitmentReconciliationInterval, [
      mkVoteRequest(configWithAcsCommitmentReconciliationInterval),
    ]);
    expect(intersectChanges2).toStrictEqual({
      hasConflict: true,
      intersection: ['decentralizedSynchronizer.synchronizers.acsCommitmentReconciliationInterval'],
    });
    const intersectChanges3 = hasConflictingFields(configWithTwoChanges, [
      mkVoteRequest(configWithTwoChanges),
    ]);
    expect(intersectChanges3).toStrictEqual({
      hasConflict: true,
      intersection: [
        'numMemberTrafficContractsThreshold',
        'decentralizedSynchronizer.synchronizers.acsCommitmentReconciliationInterval',
      ],
    });
    const intersectChanges4 = hasConflictingFields(configWithTwoChanges, [
      mkVoteRequest(configWithNumMemberTrafficContractsThresholdChange),
      mkVoteRequest(configWithAcsCommitmentReconciliationInterval),
    ]);
    expect(intersectChanges4).toStrictEqual({
      hasConflict: true,
      intersection: [
        'numMemberTrafficContractsThreshold',
        'decentralizedSynchronizer.synchronizers.acsCommitmentReconciliationInterval',
      ],
    });

    const changesAgainstOldAction = hasConflictingFields(
      configWithNumMemberTrafficContractsThresholdChange,
      [mkVoteRequest(configWithNumMemberTrafficContractsThresholdChangeOldAction)]
    );
    expect(changesAgainstOldAction).toStrictEqual({
      hasConflict: false,
      intersection: [],
    });
  });

  test('hasConflictingFields function works as expected for AmuletRulesConfig changes', async () => {
    const configWithNoChange = getAmuletSetConfigAction(
      { new: '4', base: '4' },
      { new: '4', base: '4' }
    );
    const configWithNumMemberTrafficContractsThresholdChange = getAmuletSetConfigAction(
      { new: '8', base: '4' },
      { new: '4', base: '4' }
    );
    const configWithAcsCommitmentReconciliationInterval = getAmuletSetConfigAction(
      { new: '4', base: '4' },
      { new: '8', base: '4' }
    );
    const configWithTwoChanges = getAmuletSetConfigAction(
      { new: '15', base: '4' },
      { new: '16', base: '4' }
    );

    const noChanges1 = hasConflictingFields(configWithNoChange, []);
    expect(noChanges1).toStrictEqual({ hasConflict: false, intersection: [] });
    const noChanges2 = hasConflictingFields(configWithNoChange, [
      mkVoteRequest(configWithNoChange),
    ]);
    expect(noChanges2).toStrictEqual({ hasConflict: false, intersection: [] });

    const overlapChanges1 = hasConflictingFields(
      configWithNumMemberTrafficContractsThresholdChange,
      [mkVoteRequest(configWithNoChange)]
    );
    expect(overlapChanges1).toStrictEqual({ hasConflict: false, intersection: [] });
    const overlapChanges2 = hasConflictingFields(
      configWithNumMemberTrafficContractsThresholdChange,
      [mkVoteRequest(configWithAcsCommitmentReconciliationInterval)]
    );
    expect(overlapChanges2).toStrictEqual({ hasConflict: false, intersection: [] });
    const overlapChanges3 = hasConflictingFields(configWithTwoChanges, [
      mkVoteRequest(configWithNoChange),
    ]);
    expect(overlapChanges3).toStrictEqual({ hasConflict: false, intersection: [] });

    const intersectChanges1 = hasConflictingFields(
      configWithNumMemberTrafficContractsThresholdChange,
      [mkVoteRequest(configWithNumMemberTrafficContractsThresholdChange)]
    );
    expect(intersectChanges1).toStrictEqual({
      hasConflict: true,
      intersection: ['transferConfig.createFee.fee'],
    });
    const intersectChanges2 = hasConflictingFields(configWithAcsCommitmentReconciliationInterval, [
      mkVoteRequest(configWithAcsCommitmentReconciliationInterval),
    ]);
    expect(intersectChanges2).toStrictEqual({
      hasConflict: true,
      intersection: [
        'decentralizedSynchronizer.fees.baseRateTrafficLimits.burstWindow.microseconds',
      ],
    });
    const intersectChanges3 = hasConflictingFields(configWithTwoChanges, [
      mkVoteRequest(configWithTwoChanges),
    ]);
    expect(intersectChanges3).toStrictEqual({
      hasConflict: true,
      intersection: [
        'decentralizedSynchronizer.fees.baseRateTrafficLimits.burstWindow.microseconds',
        'transferConfig.createFee.fee',
      ],
    });
    const intersectChanges4 = hasConflictingFields(configWithTwoChanges, [
      mkVoteRequest(configWithNumMemberTrafficContractsThresholdChange),
      mkVoteRequest(configWithAcsCommitmentReconciliationInterval),
    ]);
    expect(intersectChanges4).toStrictEqual({
      hasConflict: true,
      intersection: [
        'transferConfig.createFee.fee',
        'decentralizedSynchronizer.fees.baseRateTrafficLimits.burstWindow.microseconds',
      ],
    });
  });

  test('hasConflictingFields function works as expected for SV Offboarding changes', async () => {
    const aliceOffboarding = getDsoSvOffboardingAction('alice');
    const bobOffboarding = getDsoSvOffboardingAction('bob');

    const noChanges = hasConflictingFields(aliceOffboarding, []);
    expect(noChanges).toStrictEqual({ hasConflict: false, intersection: [] });

    const overlapChanges = hasConflictingFields(aliceOffboarding, [mkVoteRequest(bobOffboarding)]);
    expect(overlapChanges).toStrictEqual({ hasConflict: false, intersection: [] });

    const intersectChanges = hasConflictingFields(aliceOffboarding, [
      mkVoteRequest(aliceOffboarding),
      mkVoteRequest(bobOffboarding),
    ]);
    expect(intersectChanges).toStrictEqual({
      hasConflict: true,
      intersection: ['alice'],
    });
  });

  test('hasConflictingFields function works as expected for UpdateSvRewardWeight changes', async () => {
    const aliceUpdateSvRewardWeight = getUpdateSvRewardWeightAction('alice');
    const bobUpdateSvRewardWeight = getUpdateSvRewardWeightAction('bob');

    const noChanges = hasConflictingFields(aliceUpdateSvRewardWeight, []);
    expect(noChanges).toStrictEqual({ hasConflict: false, intersection: [] });

    const overlapChanges = hasConflictingFields(aliceUpdateSvRewardWeight, [
      mkVoteRequest(bobUpdateSvRewardWeight),
    ]);
    expect(overlapChanges).toStrictEqual({ hasConflict: false, intersection: [] });

    const intersectChanges = hasConflictingFields(aliceUpdateSvRewardWeight, [
      mkVoteRequest(aliceUpdateSvRewardWeight),
      mkVoteRequest(bobUpdateSvRewardWeight),
    ]);
    expect(intersectChanges).toStrictEqual({
      hasConflict: true,
      intersection: ['alice'],
    });
  });

  test('formatBasisPoints function works as expected', () => {
    expect(formatBasisPoints('0')).toBe('0_0000');
    expect(formatBasisPoints('1')).toBe('0_0001');
    expect(formatBasisPoints('1010')).toBe('0_1010');
    expect(formatBasisPoints('12345')).toBe('1_2345');
  });
});
