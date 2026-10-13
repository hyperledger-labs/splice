// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import * as damlTypes from '@daml/types';
import type {
  SynchronizerConfig,
  SynchronizerState,
} from '@daml.js/splice-dso-governance/lib/Splice/DSO/DecentralizedSynchronizer/module';
import type { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import type { ConfigChange } from './types';
import { configValueToSwitchOverMap } from '../components/forms/formValidators';

/**
 * Given a list of config changes, build and return a DsoRulesConfig.
 * The config changes should have all fields, whether they have been changed or not.
 */
export function buildDsoRulesConfigFromChanges(dsoConfigChanges: ConfigChange[]): DsoRulesConfig {
  // map of field names -> new values for quick lookup
  const changeMap = new Map<string, string>();

  dsoConfigChanges.forEach(change => {
    changeMap.set(change.fieldName, change.newValue);
  });

  function getValue(fieldName: string, nullable: true): string | null;
  function getValue(fieldName: string, nullable: false): string;
  function getValue(fieldName: string, nullable: boolean): string | null {
    const value = changeMap.get(fieldName);
    return value === undefined || value === '' ? (nullable ? null : '') : value;
  }

  const synchronizerCount = Array.from(changeMap.keys()).filter(key =>
    key.match(/^decentralizedSynchronizer\d+$/)
  ).length;

  let synchronizers = damlTypes.emptyMap<string, SynchronizerConfig>();

  for (let i = 1; i <= synchronizerCount; i++) {
    const key = getValue(`decentralizedSynchronizer${i}`, false);
    const value = {
      state: getValue(`decentralizedSynchronizerState${i}`, false) as SynchronizerState,
      cometBftGenesisJson: getValue(`decentralizedSynchronizerCometBftGenesisJson${i}`, false),
      acsCommitmentReconciliationInterval: getValue(
        `decentralizedSynchronizerAcsCommitmentReconciliationInterval${i}`,
        true
      ),
    };
    synchronizers = synchronizers.set(key, value);
  }

  const upgradeTime = getValue('nextScheduledSynchronizerUpgradeTime', true);
  const logicalSyncUpgradeTopologyFreezeTime = getValue(
    'nextScheduledLogicalSynchronizerUpgradeTopologyFreezeTime',
    true
  );
  const voteCooldownTime = getValue('voteCooldownTime', true);

  const svOperationsSwitchOverTimes = configValueToSwitchOverMap(
    getValue('svOperationsSwitchOverTimes', true)
  );

  const dsoConfig: DsoRulesConfig = {
    numUnclaimedRewardsThreshold: getValue('numUnclaimedRewardsThreshold', false),
    numMemberTrafficContractsThreshold: getValue('numMemberTrafficContractsThreshold', false),
    actionConfirmationTimeout: {
      microseconds: getValue('actionConfirmationTimeout', false),
    },
    svOnboardingRequestTimeout: {
      microseconds: getValue('svOnboardingRequestTimeout', false),
    },
    svOnboardingConfirmedTimeout: {
      microseconds: getValue('svOnboardingConfirmedTimeout', false),
    },
    maxTextLength: getValue('maxTextLength', false),
    voteRequestTimeout: {
      microseconds: getValue('voteRequestTimeout', false),
    },
    dsoDelegateInactiveTimeout: {
      microseconds: getValue('dsoDelegateInactiveTimeout', false),
    },
    synchronizerNodeConfigLimits: {
      cometBft: {
        maxNumSequencingKeys: getValue(
          'synchronizerNodeConfigLimitsCometBftMaxNumSequencingKeys',
          false
        ),
        maxNodeIdLength: getValue('synchronizerNodeConfigLimitsCometBftMaxNodeIdLength', false),
        maxNumGovernanceKeys: getValue(
          'synchronizerNodeConfigLimitsCometBftMaxNumGovernanceKeys',
          false
        ),
        maxNumCometBftNodes: getValue(
          'synchronizerNodeConfigLimitsCometBftMaxNumCometBftNodes',
          false
        ),
        maxPubKeyLength: getValue('synchronizerNodeConfigLimitsCometBftMaxPubKeyLength', false),
      },
    },
    decentralizedSynchronizer: {
      lastSynchronizerId: getValue('decentralizedSynchronizerLastSynchronizerId', false),
      activeSynchronizerId: getValue('decentralizedSynchronizerActiveSynchronizerId', false),
      synchronizers: synchronizers,
    },

    nextScheduledSynchronizerUpgrade:
      upgradeTime === null
        ? null
        : {
            time: upgradeTime,
            migrationId: getValue('nextScheduledSynchronizerUpgradeMigrationId', false),
          },
    nextScheduledLogicalSynchronizerUpgrade:
      logicalSyncUpgradeTopologyFreezeTime === null
        ? null
        : {
            topologyFreezeTime: logicalSyncUpgradeTopologyFreezeTime,
            upgradeTime: getValue('nextScheduledLogicalSynchronizerUpgradeUpgradeTime', false),
            newPhysicalSynchronizerSerial: getValue(
              'nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerSerial',
              false
            ),
            newPhysicalSynchronizerProtocolVersion: getValue(
              'nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerProtocolVersion',
              false
            ),
          },
    voteCooldownTime: voteCooldownTime === null ? null : { microseconds: voteCooldownTime },
    svOperationsSwitchOverTimes: svOperationsSwitchOverTimes,
  };

  return dsoConfig;
}
