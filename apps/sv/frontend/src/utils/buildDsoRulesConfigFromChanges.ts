// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import * as damlTypes from '@daml/types';
import type {
  SynchronizerConfig,
  SynchronizerState,
} from '@daml.js/splice-dso-governance/lib/Splice/DSO/DecentralizedSynchronizer/module';
import type { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import type { ConfigChange } from './types';

/**
 * Given a list of config changes, build and return a DsoRulesConfig.
 * The config changes should have all fields, whether they have been changed or not.
 */
export function buildDsoRulesConfigFromChanges(dsoConfigChanges: ConfigChange[]): DsoRulesConfig {
  // map of field names -> new values for quick lookup
  const changeMap = new Map<string, string>();

  dsoConfigChanges.forEach(change => {
    changeMap.set(change.fieldName, change.newValue.toString());
  });

  const getValue = (fieldName: string, fallbackValue: string = '') => {
    const value = changeMap.get(fieldName);
    return value ? value : fallbackValue;
  };

  const synchronizerCount = Array.from(changeMap.keys()).filter(key =>
    key.match(/^decentralizedSynchronizer\d+$/)
  ).length;

  let synchronizers = damlTypes.emptyMap<string, SynchronizerConfig>();

  for (let i = 1; i <= synchronizerCount; i++) {
    const key = getValue(`decentralizedSynchronizer${i}`);
    const value = {
      state: getValue(`decentralizedSynchronizerState${i}`) as SynchronizerState,
      cometBftGenesisJson: getValue(`decentralizedSynchronizerCometBftGenesisJson${i}`),
      acsCommitmentReconciliationInterval: getValue(
        `decentralizedSynchronizerAcsCommitmentReconciliationInterval${i}`
      ),
    };
    synchronizers = synchronizers.set(key, value);
  }

  const upgradeTime = getValue('nextScheduledSynchronizerUpgradeTime');
  const upgradeMigrationId = getValue('nextScheduledSynchronizerUpgradeMigrationId');
  const voteCooldownTime = getValue('voteCooldownTime');
  const voteExecutionInstructionTimeout = getValue('voteExecutionInstructionTimeout');

  const dsoConfig: DsoRulesConfig = {
    numUnclaimedRewardsThreshold: getValue('numUnclaimedRewardsThreshold'),
    numMemberTrafficContractsThreshold: getValue('numMemberTrafficContractsThreshold'),
    actionConfirmationTimeout: {
      microseconds: getValue('actionConfirmationTimeout'),
    },
    svOnboardingRequestTimeout: {
      microseconds: getValue('svOnboardingRequestTimeout'),
    },
    svOnboardingConfirmedTimeout: {
      microseconds: getValue('svOnboardingConfirmedTimeout'),
    },
    maxTextLength: getValue('maxTextLength'),
    voteRequestTimeout: {
      microseconds: getValue('voteRequestTimeout'),
    },
    dsoDelegateInactiveTimeout: {
      microseconds: getValue('dsoDelegateInactiveTimeout'),
    },
    synchronizerNodeConfigLimits: {
      cometBft: {
        maxNumSequencingKeys: getValue('synchronizerNodeConfigLimitsCometBftMaxNumSequencingKeys'),
        maxNodeIdLength: getValue('synchronizerNodeConfigLimitsCometBftMaxNodeIdLength'),
        maxNumGovernanceKeys: getValue('synchronizerNodeConfigLimitsCometBftMaxNumGovernanceKeys'),
        maxNumCometBftNodes: getValue('synchronizerNodeConfigLimitsCometBftMaxNumCometBftNodes'),
        maxPubKeyLength: getValue('synchronizerNodeConfigLimitsCometBftMaxPubKeyLength'),
      },
    },
    decentralizedSynchronizer: {
      lastSynchronizerId: getValue('decentralizedSynchronizerLastSynchronizerId'),
      activeSynchronizerId: getValue('decentralizedSynchronizerActiveSynchronizerId'),
      synchronizers: synchronizers,
    },

    nextScheduledSynchronizerUpgrade:
      upgradeTime && upgradeTime !== ''
        ? { time: upgradeTime, migrationId: upgradeMigrationId }
        : null,
    voteCooldownTime:
      voteCooldownTime && voteCooldownTime !== '' ? { microseconds: voteCooldownTime } : null,
    voteExecutionInstructionTimeout:
      voteExecutionInstructionTimeout && voteExecutionInstructionTimeout !== ''
        ? { microseconds: voteExecutionInstructionTimeout }
        : null,
  };

  return dsoConfig;
}
