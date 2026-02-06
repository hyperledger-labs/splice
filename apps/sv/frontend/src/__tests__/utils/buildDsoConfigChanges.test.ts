// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, test } from 'vitest';
import { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { RecursivePartial } from './types';
import { buildDsoConfigChanges } from '../../utils/buildDsoConfigChanges';

describe('buildDsoConfigChanges', () => {
  test('should mark decentralized synchronizer fields as disabled', () => {
    const mockConfig: RecursivePartial<DsoRulesConfig> = {
      actionConfirmationTimeout: { microseconds: '1000' },
      svOnboardingRequestTimeout: { microseconds: '1000' },
      svOnboardingConfirmedTimeout: { microseconds: '1000' },
      voteRequestTimeout: { microseconds: '1000' },
      dsoDelegateInactiveTimeout: { microseconds: '1000' },
      synchronizerNodeConfigLimits: {
        cometBft: {
          maxNumCometBftNodes: '1',
          maxNumGovernanceKeys: '1',
          maxNumSequencingKeys: '1',
          maxNodeIdLength: '1',
          maxPubKeyLength: '1',
        },
      },
      decentralizedSynchronizer: {
        lastSynchronizerId: 'sync-last',
        activeSynchronizerId: 'sync-active',
        synchronizers: {
          entriesArray: () => [
            [
              'sync1',
              {
                state: 'DS_Operational',
                cometBftGenesisJson: '{}',
                acsCommitmentReconciliationInterval: '100',
              },
            ],
            [
              'sync2',
              {
                state: 'DS_Bootstrapping',
                cometBftGenesisJson: '{}',
                acsCommitmentReconciliationInterval: '100',
              },
            ],
          ],
        },
      },
    };

    const changes = buildDsoConfigChanges(
      mockConfig as DsoRulesConfig,
      mockConfig as DsoRulesConfig,
      true
    );

    const disabledFieldPatterns = [
      /^decentralizedSynchronizerLastSynchronizerId$/,
      /^decentralizedSynchronizerActiveSynchronizerId$/,
      /^decentralizedSynchronizer\d+$/,
      /^decentralizedSynchronizerState\d+$/,
      /^decentralizedSynchronizerCometBftGenesisJson\d+$/,
    ];

    changes.forEach(change => {
      if (disabledFieldPatterns.some(pattern => pattern.test(change.fieldName))) {
        expect(change.disabled).toBe(true);
      } else {
        expect(change.disabled).toBeFalsy();
      }
    });
  });
});
