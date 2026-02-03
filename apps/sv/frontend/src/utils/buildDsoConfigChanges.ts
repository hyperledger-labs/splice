// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { DsoDecentralizedSynchronizerConfig } from '@daml.js/splice-dso-governance/lib/Splice/DSO/DecentralizedSynchronizer/module';
import { Optional } from '@daml/types';
import { ConfigChange } from './types';

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
          label: `Decentralized Synchronizer ${idx}: Synchronizer ID`,
          currentValue: baseSynchronizer[0] || '',
          newValue: currentSynchronizer?.[0] || '',
          isId: true,
        },
        {
          fieldName: `decentralizedSynchronizerState${idx}`,
          label: `Decentralized Synchronizer ${idx}: State`,
          currentValue: baseSynchronizer[1].state || '',
          newValue: currentSynchronizer?.[1].state || '',
        },
        {
          fieldName: `decentralizedSynchronizerCometBftGenesisJson${idx}`,
          label: `Decentralized Synchronizer ${idx}: CometBft Genesis (Json)`,
          currentValue: baseSynchronizer[1].cometBftGenesisJson || '',
          newValue: currentSynchronizer?.[1].cometBftGenesisJson || '',
        },
        {
          fieldName: `decentralizedSynchronizerAcsCommitmentReconciliationInterval${idx}`,
          label: `Decentralized Synchronizer ${idx}: ACS Commitment Reconciliation Interval`,
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
      label: 'Minimum Number of Unclaimed Rewards Contracts Required For Merging',
      currentValue: before?.numUnclaimedRewardsThreshold || '',
      newValue: after?.numUnclaimedRewardsThreshold || '',
    },
    {
      fieldName: 'numMemberTrafficContractsThreshold',
      label: 'Minimum Number of Member Traffic Contracts Required For Merging',
      currentValue: before?.numMemberTrafficContractsThreshold || '',
      newValue: after?.numMemberTrafficContractsThreshold || '',
    },
    {
      fieldName: 'actionConfirmationTimeout',
      label: 'TTL For Contracts Representing A Confirmation Of An Action',
      currentValue: before?.actionConfirmationTimeout.microseconds || '',
      newValue: after?.actionConfirmationTimeout.microseconds || '',
    },
    {
      fieldName: 'svOnboardingRequestTimeout',
      label: 'TTL For Contracts Representing An Incomplete SV Onboarding',
      currentValue: before?.svOnboardingRequestTimeout.microseconds || '',
      newValue: after?.svOnboardingRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'svOnboardingConfirmedTimeout',
      label: 'TTL For Contracts Representing Confirmation For An SV To Onboard',
      currentValue: before?.svOnboardingConfirmedTimeout.microseconds || '',
      newValue: after?.svOnboardingConfirmedTimeout.microseconds || '',
    },
    {
      fieldName: 'maxTextLength',
      label: 'Generic Upper Limit On Text Fields',
      currentValue: before?.maxTextLength || '',
      newValue: after?.maxTextLength || '',
    },
    {
      fieldName: 'voteRequestTimeout',
      label: 'TTL For Contracts Representing Vote Requests and Votes',
      currentValue: before?.voteRequestTimeout.microseconds || '',
      newValue: after?.voteRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'dsoDelegateInactiveTimeout',
      label: '(Deprecated) DSO Delegate Inactivity Timeout',
      currentValue: before?.dsoDelegateInactiveTimeout.microseconds || '',
      newValue: after?.dsoDelegateInactiveTimeout.microseconds || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumCometBftNodes',
      label: 'SV Node Limits: CometBFT: Maximum Number of CometBft Nodes',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumGovernanceKeys',
      label: 'SV Node Limits: CometBFT: Maximum Number of Governance Keys',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumSequencingKeys',
      label: 'SV Node Limits: CometBFT: Maximum Number of Sequencing Keys',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNodeIdLength',
      label: 'SV Node Limits: CometBFT: Maximum Node ID Length',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxPubKeyLength',
      label: 'SV Node Limits: CometBFT: Maximum Public Key Length',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
    },

    ...buildSynchronizerMap(before?.decentralizedSynchronizer, after?.decentralizedSynchronizer),

    {
      fieldName: 'decentralizedSynchronizerLastSynchronizerId',
      label: 'Decentralized Synchronizer: Last Synchronizer ID',
      currentValue: before?.decentralizedSynchronizer.lastSynchronizerId || '',
      newValue: after?.decentralizedSynchronizer.lastSynchronizerId || '',
      isId: true,
    },
    {
      fieldName: 'decentralizedSynchronizerActiveSynchronizerId',
      label: 'Decentralized Synchronizer: Active Synchronizer ID',
      currentValue: before?.decentralizedSynchronizer.activeSynchronizerId || '',
      newValue: after?.decentralizedSynchronizer.activeSynchronizerId || '',
      isId: true,
    },
    {
      fieldName: 'nextScheduledSynchronizerUpgradeTime',
      label: 'Next Scheduled Synchronizer Upgrade Time',
      currentValue: before?.nextScheduledSynchronizerUpgrade?.time || '',
      newValue: after?.nextScheduledSynchronizerUpgrade?.time || '',
    },
    {
      fieldName: 'nextScheduledSynchronizerUpgradeMigrationId',
      label: 'Next Scheduled Synchronizer Upgrade Migration ID',
      currentValue: before?.nextScheduledSynchronizerUpgrade?.migrationId || '',
      newValue: after?.nextScheduledSynchronizerUpgrade?.migrationId || '',
    },
    {
      fieldName: 'voteCooldownTime',
      label: 'The Minimum Time Between Two Votes (or Vote Changes) By The Same SV',
      currentValue: before?.voteCooldownTime?.microseconds || '',
      newValue: after?.voteCooldownTime?.microseconds || '',
    },
  ] as ConfigChange[];

  return showAllFields ? changes : changes.filter(c => c.currentValue !== c.newValue);
}
