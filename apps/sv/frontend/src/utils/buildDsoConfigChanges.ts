// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { DsoDecentralizedSynchronizerConfig } from '@daml.js/splice-dso-governance/lib/Splice/DSO/DecentralizedSynchronizer/module';
import { Optional } from '@daml/types';
import { ConfigChange } from './types';
import { switchOverMapToConfigValue } from '../components/forms/formValidators';

function buildSynchronizerMap(
  baseConfig: DsoDecentralizedSynchronizerConfig | undefined,
  currentConfig: DsoDecentralizedSynchronizerConfig | undefined
): ConfigChange[] {
  const baseSynchronizers = baseConfig?.synchronizers.entriesArray();
  const currentSynchronizers = currentConfig?.synchronizers.entriesArray();

  const res = baseSynchronizers
    ?.map((baseSynchronizer, index) => {
      const currentSynchronizer = currentSynchronizers?.find(c => c[0] === baseSynchronizer[0]);

      // normalise the index displayed
      const idx = index + 1;
      return [
        {
          fieldName: `decentralizedSynchronizer${idx}`,
          label: `Decentralized synchronizer ${idx}: Synchronizer identifier`,
          currentValue: baseSynchronizer[0] || '',
          newValue: currentSynchronizer?.[0] || '',
          isId: true,
          disabled: true,
        },
        {
          fieldName: `decentralizedSynchronizerState${idx}`,
          label: `Decentralized synchronizer ${idx}: State`,
          currentValue: baseSynchronizer[1].state || '',
          newValue: currentSynchronizer?.[1].state || '',
          disabled: true,
        },
        {
          fieldName: `decentralizedSynchronizerCometBftGenesisJson${idx}`,
          label: `Decentralized synchronizer ${idx}: CometBFT genesis (JSON)`,
          currentValue: baseSynchronizer[1].cometBftGenesisJson || '',
          newValue: currentSynchronizer?.[1].cometBftGenesisJson || '',
          disabled: true,
        },
        {
          fieldName: `decentralizedSynchronizerAcsCommitmentReconciliationInterval${idx}`,
          label: `Decentralized synchronizer ${idx}: Active Contract Set commitment reconciliation interval`,
          currentValue: baseSynchronizer[1].acsCommitmentReconciliationInterval || '',
          newValue: currentSynchronizer?.[1].acsCommitmentReconciliationInterval || '',
        },
      ] as ConfigChange[];
    })
    .flat();

  return res || [];
}

/**
 * Given 2 configs, return the changes between them
 * @param before the base config
 * @param after the config with changes
 * @param showAllFields if true, do not filter out fields that have not changed
 * @returns the changes between the 2 configs
 */
export function buildDsoConfigChanges(
  before: Optional<DsoRulesConfig>,
  after: Optional<DsoRulesConfig>,
  showAllFields: boolean = false
): ConfigChange[] {
  const changes = [
    {
      fieldName: 'numUnclaimedRewardsThreshold',
      label: 'Minimum number of unclaimedRewards contracts required for merging',
      currentValue: before?.numUnclaimedRewardsThreshold || '',
      newValue: after?.numUnclaimedRewardsThreshold || '',
    },
    {
      fieldName: 'numMemberTrafficContractsThreshold',
      label: 'Minimum number of memberTraffic contracts required For merging',
      currentValue: before?.numMemberTrafficContractsThreshold || '',
      newValue: after?.numMemberTrafficContractsThreshold || '',
    },
    {
      fieldName: 'svOnboardingRequestTimeout',
      label: 'Time-To-Live for contracts representing an incomplete Super Validator onboarding',
      currentValue: before?.svOnboardingRequestTimeout.microseconds || '',
      newValue: after?.svOnboardingRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'actionConfirmationTimeout',
      label: 'Time-To-Live for contracts representing a confirmation of an action',
      currentValue: before?.actionConfirmationTimeout.microseconds || '',
      newValue: after?.actionConfirmationTimeout.microseconds || '',
    },
    {
      fieldName: 'svOnboardingConfirmedTimeout',
      label:
        'Time-To-Live for contracts representing confirmation for a Super Validator to onboard',
      currentValue: before?.svOnboardingConfirmedTimeout.microseconds || '',
      newValue: after?.svOnboardingConfirmedTimeout.microseconds || '',
    },
    {
      fieldName: 'voteRequestTimeout',
      label: 'Time-To-Live for contracts representing vote requests and votes',
      currentValue: before?.voteRequestTimeout.microseconds || '',
      newValue: after?.voteRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'dsoDelegateInactiveTimeout',
      label: '(Deprecated) Decentralized Synchronizer Operations (DSO) delegate inactivity timeout',
      currentValue: before?.dsoDelegateInactiveTimeout.microseconds || '',
      newValue: after?.dsoDelegateInactiveTimeout.microseconds || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumCometBftNodes',
      label: 'Super Validator node limits: CometBFT: Maximum number of CometBFT nodes',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumGovernanceKeys',
      label: 'Super Validator node limits: CometBFT: Maximum number of governance keys',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumSequencingKeys',
      label: 'Super Validator node limits: CometBFT: Maximum number of sequencing keys',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNodeIdLength',
      label: 'Super Validator node limits: CometBFT: Maximum node identifier length',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxPubKeyLength',
      label: 'Super Validator node limits: CometBFT: Maximum public key length',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
    },
    {
      fieldName: 'maxTextLength',
      label: 'Generic upper limit on text fields',
      currentValue: before?.maxTextLength || '',
      newValue: after?.maxTextLength || '',
    },

    ...buildSynchronizerMap(before?.decentralizedSynchronizer, after?.decentralizedSynchronizer),

    {
      fieldName: 'decentralizedSynchronizerLastSynchronizerId',
      label: 'Decentralized synchronizer: Last synchronizer identifier',
      currentValue: before?.decentralizedSynchronizer.lastSynchronizerId || '',
      newValue: after?.decentralizedSynchronizer.lastSynchronizerId || '',
      isId: true,
      disabled: true,
    },
    {
      fieldName: 'decentralizedSynchronizerActiveSynchronizerId',
      label: 'Decentralized synchronizer: Active synchronizer identifier',
      currentValue: before?.decentralizedSynchronizer.activeSynchronizerId || '',
      newValue: after?.decentralizedSynchronizer.activeSynchronizerId || '',
      isId: true,
      disabled: true,
    },
    {
      fieldName: 'nextScheduledSynchronizerUpgradeTime',
      label: 'Next scheduled synchronizer upgrade time',
      currentValue: before?.nextScheduledSynchronizerUpgrade?.time || '',
      newValue: after?.nextScheduledSynchronizerUpgrade?.time || '',
    },
    {
      fieldName: 'nextScheduledSynchronizerUpgradeMigrationId',
      label: 'Next scheduled synchronizer upgrade migration identifier',
      currentValue: before?.nextScheduledSynchronizerUpgrade?.migrationId || '',
      newValue: after?.nextScheduledSynchronizerUpgrade?.migrationId || '',
    },
    {
      fieldName: 'nextScheduledLogicalSynchronizerUpgradeTopologyFreezeTime',
      label: 'Next scheduled logical synchronizer upgrade topology freeze time',
      currentValue: before?.nextScheduledLogicalSynchronizerUpgrade?.topologyFreezeTime || '',
      newValue: after?.nextScheduledLogicalSynchronizerUpgrade?.topologyFreezeTime || '',
    },
    {
      fieldName: 'nextScheduledLogicalSynchronizerUpgradeUpgradeTime',
      label: 'Next scheduled logical synchronizer upgrade upgrade time',
      currentValue: before?.nextScheduledLogicalSynchronizerUpgrade?.upgradeTime || '',
      newValue: after?.nextScheduledLogicalSynchronizerUpgrade?.upgradeTime || '',
    },
    {
      fieldName: 'nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerSerial',
      label: 'Next scheduled logical synchronizer upgrade new physical synchronizer serial',
      currentValue:
        before?.nextScheduledLogicalSynchronizerUpgrade?.newPhysicalSynchronizerSerial || '',
      newValue: after?.nextScheduledLogicalSynchronizerUpgrade?.newPhysicalSynchronizerSerial || '',
    },
    {
      fieldName: 'nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerProtocolVersion',
      label:
        'Next scheduled logical synchronizer upgrade new physical synchronizer protocol version',
      currentValue:
        before?.nextScheduledLogicalSynchronizerUpgrade?.newPhysicalSynchronizerProtocolVersion ||
        '',
      newValue:
        after?.nextScheduledLogicalSynchronizerUpgrade?.newPhysicalSynchronizerProtocolVersion ||
        '',
    },
    {
      fieldName: 'voteCooldownTime',
      label: 'The minimum time between two votes (or vote changes) by the same Super Validator',
      currentValue: before?.voteCooldownTime?.microseconds || '',
      newValue: after?.voteCooldownTime?.microseconds || '',
    },
    {
      fieldName: 'svOperationsSwitchOverTimes',
      label: 'SV operations switch-over times',
      currentValue: switchOverMapToConfigValue(before?.svOperationsSwitchOverTimes),
      newValue: switchOverMapToConfigValue(after?.svOperationsSwitchOverTimes),
    },
  ] as ConfigChange[];

  return showAllFields ? changes : changes.filter(c => c.currentValue !== c.newValue);
}
