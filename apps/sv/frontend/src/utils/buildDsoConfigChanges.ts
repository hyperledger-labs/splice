// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { DsoDecentralizedSynchronizerConfig } from '@daml.js/splice-dso-governance/lib/Splice/DSO/DecentralizedSynchronizer/module';
import { Optional } from '@daml/types';
import { ConfigChange } from './types';

export function buildSynchronizerMap(
  baseConfig: DsoDecentralizedSynchronizerConfig | undefined,
  currentConfig: DsoDecentralizedSynchronizerConfig | undefined
): ConfigChange[] {
  const baseSynchronizers = baseConfig?.synchronizers.entriesArray();
  const currentSynchronizers = currentConfig?.synchronizers.entriesArray();

  const res = baseSynchronizers
    ?.map(baseSynchronizer => {
      const currentSynchronizer = currentSynchronizers?.find(c => c[0] === baseSynchronizer[0]);
      return [
        {
          fieldName: 'decentralizedSynchronizer',
          label: `Decentralized Synchronizer`,
          currentValue: baseSynchronizer[0] || '',
          newValue: currentSynchronizer?.[0] || '',
          isId: true,
        },
        {
          fieldName: 'decentralizedSynchronizerState',
          label: `Decentralized Synchronizer (state)`,
          currentValue: baseSynchronizer[1].state || '',
          newValue: currentSynchronizer?.[1].state || '',
        },
        {
          fieldName: 'decentralizedSynchronizerCometBftGenesisJson',
          label: `Decentralized Synchronizer (cometBftGenesisJson)`,
          currentValue: baseSynchronizer[1].cometBftGenesisJson || '',
          newValue: currentSynchronizer?.[1].cometBftGenesisJson || '',
        },
        {
          fieldName: 'decentralizedSynchronizerAcsCommitmentReconciliationInterval',
          label: `Decentralized Synchronizer (ACS Commitment Reconciliation Interval)`,
          currentValue: baseSynchronizer[1].acsCommitmentReconciliationInterval || '',
          newValue: currentSynchronizer?.[1].acsCommitmentReconciliationInterval || '',
        },
      ] as ConfigChange[];
    })
    .flat();

  return res || [];
}

export function buildDsoConfigChanges(
  before: Optional<DsoRulesConfig>,
  after: Optional<DsoRulesConfig>,
  showAllFields: boolean = false
): ConfigChange[] {
  const changes = [
    {
      fieldName: 'numUnclaimedRewardsThreshold',
      label: 'Number of Unclaimed Rewards Threshold',
      currentValue: before?.numUnclaimedRewardsThreshold || '',
      newValue: after?.numUnclaimedRewardsThreshold || '',
    },
    {
      fieldName: 'numMemberTrafficContractsThreshold',
      label: 'Number of Member Traffic Contracts Threshold',
      currentValue: before?.numMemberTrafficContractsThreshold || '',
      newValue: after?.numMemberTrafficContractsThreshold || '',
    },
    {
      fieldName: 'actionConfirmationTimeout',
      label: 'Action Confirmation Timeout',
      currentValue: before?.actionConfirmationTimeout.microseconds || '',
      newValue: after?.actionConfirmationTimeout.microseconds || '',
    },
    {
      fieldName: 'svOnboardingRequestTimeout',
      label: 'SV Onboarding Request Timeout',
      currentValue: before?.svOnboardingRequestTimeout.microseconds || '',
      newValue: after?.svOnboardingRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'svOnboardingConfirmedTimeout',
      label: 'SV Onboarding Confirmed Timeout',
      currentValue: before?.svOnboardingConfirmedTimeout.microseconds || '',
      newValue: after?.svOnboardingConfirmedTimeout.microseconds || '',
    },
    {
      fieldName: 'maxTextLength',
      label: 'Max Text Length',
      currentValue: before?.maxTextLength || '',
      newValue: after?.maxTextLength || '',
    },
    {
      fieldName: 'voteRequestTimeout',
      label: 'Vote Request Timeout',
      currentValue: before?.voteRequestTimeout.microseconds || '',
      newValue: after?.voteRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'dsoDelegateInactiveTimeout',
      label: 'DSO Delegate Inactivity Timeout',
      currentValue: before?.dsoDelegateInactiveTimeout.microseconds || '',
      newValue: after?.dsoDelegateInactiveTimeout.microseconds || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumCometBftNodes',
      label: 'Synchronizer Node Config Limits (maxNumCometBftNodes)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumGovernanceKeys',
      label: 'Synchronizer Node Config Limits (maxNumGovernanceKeys)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumSequencingKeys',
      label: 'Synchronizer Node Config Limits (maxNumSequencingKeys)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNodeIdLength',
      label: 'Synchronizer Node Config Limits (maxNodeIdLength)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxPubKeyLength',
      label: 'Synchronizer Node Config Limits (maxPubKeyLength)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
    },

    ...buildSynchronizerMap(before?.decentralizedSynchronizer, after?.decentralizedSynchronizer),

    {
      fieldName: 'decentralizedSynchronizerLastSynchronizerId',
      label: 'Decentralized Synchronizer (Last Synchronizer ID)',
      currentValue: before?.decentralizedSynchronizer.lastSynchronizerId || '',
      newValue: after?.decentralizedSynchronizer.lastSynchronizerId || '',
      isId: true,
    },
    {
      fieldName: 'decentralizedSynchronizerActiveSynchronizerId',
      label: 'Decentralized Synchronizer (Active Synchronizer ID)',
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
  ] as ConfigChange[];

  return showAllFields ? changes : changes.filter(c => c.currentValue !== c.newValue);
}
