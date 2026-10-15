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

  it('should serialize an empty nullable acsCommitmentReconciliationInterval as null, not ""', () => {
    const changes: ConfigChange[] = [
      {
        fieldName: 'decentralizedSynchronizer1',
        label: 'Sync',
        currentValue: 'sync1',
        newValue: 'sync1',
      },
      {
        fieldName: 'decentralizedSynchronizerState1',
        label: 'State',
        currentValue: 'DS_Operational',
        newValue: 'DS_Operational',
      },
      {
        fieldName: 'decentralizedSynchronizerCometBftGenesisJson1',
        label: 'Genesis',
        currentValue: 'genesis',
        newValue: 'genesis',
      },
      {
        fieldName: 'decentralizedSynchronizerAcsCommitmentReconciliationInterval1',
        label: 'Interval',
        currentValue: '',
        newValue: '',
      },
    ];
    const result = buildDsoRulesConfigFromChanges(changes);

    const syncValue = result.decentralizedSynchronizer.synchronizers.get('sync1');
    expect(syncValue?.acsCommitmentReconciliationInterval).toBeNull();
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

describe('buildDsoRulesConfigFromChanges switch-over times', () => {
  const switchOverChange = (value: string) => ({
    fieldName: 'svOperationsSwitchOverTimes',
    label: 'SV operations switch-over times',
    currentValue: '',
    newValue: value,
  });

  it('is null when the switch-over field is absent', () => {
    expect(buildDsoRulesConfigFromChanges([]).svOperationsSwitchOverTimes).toBeNull();
  });

  it('is null when the switch-over field value is empty', () => {
    const result = buildDsoRulesConfigFromChanges([switchOverChange('')]);
    expect(result.svOperationsSwitchOverTimes).toBeNull();
  });

  it('parses the serialized map back into a switch-over map', () => {
    const result = buildDsoRulesConfigFromChanges([
      switchOverChange('{"amulet-v2":"2026-09-06T00:00:00Z"}'),
    ]);
    expect(result.svOperationsSwitchOverTimes).toEqual({ 'amulet-v2': '2026-09-06T00:00:00Z' });
  });
});
