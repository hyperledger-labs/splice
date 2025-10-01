// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, it, expect } from 'vitest';
import { ConfigChange } from '../../utils/types';
import { buildDsoRulesConfigFromChanges } from '../../utils/buildDsoRulesConfigFromChanges';

describe('buildDsoRulesConfigFromChanges', () => {
  it('should build DsoRulesConfig with provided changes', () => {
    const changes: ConfigChange[] = [
      {
        fieldName: 'numUnclaimedRewardsThreshold',
        label: 'Threshold',
        currentValue: '10',
        newValue: '20',
      },
      {
        fieldName: 'actionConfirmationTimeout',
        label: 'Timeout',
        currentValue: '1000',
        newValue: '2000',
      },
      {
        fieldName: 'decentralizedSynchronizer1',
        label: 'Sync',
        currentValue: 'sync1',
        newValue: 'sync1',
      },
      {
        fieldName: 'decentralizedSynchronizerState1',
        label: 'State',
        currentValue: 'active',
        newValue: 'active',
      },
      {
        fieldName: 'decentralizedSynchronizerCometBftGenesisJson1',
        label: 'Genesis',
        currentValue: '{}',
        newValue: '{"genesis": "data"}',
      },
      {
        fieldName: 'decentralizedSynchronizerAcsCommitmentReconciliationInterval1',
        label: 'Interval',
        currentValue: '60',
        newValue: '120',
      },
    ];
    const result = buildDsoRulesConfigFromChanges(changes);

    expect(result.numUnclaimedRewardsThreshold).toBe('20');
    expect(result.actionConfirmationTimeout.microseconds).toBe('2000');
    const syncValue = result.decentralizedSynchronizer.synchronizers.get('sync1');
    expect(syncValue).toEqual({
      state: 'active',
      cometBftGenesisJson: '{"genesis": "data"}',
      acsCommitmentReconciliationInterval: '120',
    });
  });

  it('should handle nextScheduledSynchronizerUpgrade when provided', () => {
    const changes: ConfigChange[] = [];
    const result = buildDsoRulesConfigFromChanges(changes);
    expect(result.nextScheduledSynchronizerUpgrade).toBeNull();
  });

  it('should handle nextScheduledSynchronizerUpgrade when provided', () => {
    const changes: ConfigChange[] = [
      {
        fieldName: 'nextScheduledSynchronizerUpgradeTime',
        label: 'Upgrade Time',
        currentValue: '',
        newValue: '2023-01-01T00:00:00Z',
      },
      {
        fieldName: 'nextScheduledSynchronizerUpgradeMigrationId',
        label: 'Migration ID',
        currentValue: '',
        newValue: '1',
      },
    ];
    const result = buildDsoRulesConfigFromChanges(changes);

    expect(result.nextScheduledSynchronizerUpgrade).toEqual({
      time: '2023-01-01T00:00:00Z',
      migrationId: '1',
    });
  });

  it('should handle voteCooldownTime when provided', () => {
    const changes: ConfigChange[] = [];
    const result = buildDsoRulesConfigFromChanges(changes);
    expect(result.voteCooldownTime).toBeNull();
  });

  it('should handle voteCooldownTime when provided', () => {
    const changes: ConfigChange[] = [
      { fieldName: 'voteCooldownTime', label: 'Cooldown', currentValue: '', newValue: '300000' },
    ];
    const result = buildDsoRulesConfigFromChanges(changes);

    expect(result.voteCooldownTime).toEqual({ microseconds: '300000' });
  });
});
