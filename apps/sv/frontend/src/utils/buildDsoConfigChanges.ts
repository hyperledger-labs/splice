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
          fieldName: `Decentralized Synchronizer`,
          currentValue: baseSynchronizer[0] || '',
          newValue: currentSynchronizer?.[0] || '',
          isId: true,
        },
        {
          fieldName: `Decentralized Synchronizer (state)`,
          currentValue: baseSynchronizer[1].state || '',
          newValue: currentSynchronizer?.[1].state || '',
        },
        {
          fieldName: `Decentralized Synchronizer (cometBftGenesisJson)`,
          currentValue: baseSynchronizer[1].cometBftGenesisJson || '',
          newValue: currentSynchronizer?.[1].cometBftGenesisJson || '',
        },
        {
          fieldName: `Decentralized Synchronizer (ACS Commitment Reconciliation Interval)`,
          currentValue: baseSynchronizer[1].acsCommitmentReconciliationInterval || '',
          newValue: currentSynchronizer?.[1].acsCommitmentReconciliationInterval || '',
        },
      ];
    })
    .flat();

  return res || [];
}

export function buildDsoConfigChanges(
  before: Optional<DsoRulesConfig>,
  after: Optional<DsoRulesConfig>
): ConfigChange[] {
  const changes = [
    {
      fieldName: 'Number of Unclaimed Rewards Threshold',
      currentValue: before?.numUnclaimedRewardsThreshold || '',
      newValue: after?.numUnclaimedRewardsThreshold || '',
    },
    {
      fieldName: 'Number of Member Traffic Contracts Threshold',
      currentValue: before?.numMemberTrafficContractsThreshold || '',
      newValue: after?.numMemberTrafficContractsThreshold || '',
    },
    {
      fieldName: 'Action Confirmation Timeout',
      currentValue: before?.actionConfirmationTimeout.microseconds || '',
      newValue: after?.actionConfirmationTimeout.microseconds || '',
    },
    {
      fieldName: 'SV Onboarding Request Timeout',
      currentValue: before?.svOnboardingRequestTimeout.microseconds || '',
      newValue: after?.svOnboardingRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'SV Onboarding Confirmed Timeout',
      currentValue: before?.svOnboardingConfirmedTimeout.microseconds || '',
      newValue: after?.svOnboardingConfirmedTimeout.microseconds || '',
    },
    {
      fieldName: 'Vote Request Timeout',
      currentValue: before?.voteRequestTimeout.microseconds || '',
      newValue: after?.voteRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'Max Text Length',
      currentValue: before?.maxTextLength || '',
      newValue: after?.maxTextLength || '',
    },
    {
      fieldName: 'Vote Request Timeout',
      currentValue: before?.voteRequestTimeout.microseconds || '',
      newValue: after?.voteRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'DSO Delegate Inactivity Timeout',
      currentValue: before?.dsoDelegateInactiveTimeout.microseconds || '',
      newValue: after?.dsoDelegateInactiveTimeout.microseconds || '',
    },
    {
      fieldName: 'Synchronizer Node Config Limits (maxNumCometBftNodes)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
    },
    {
      fieldName: 'Synchronizer Node Config Limits (maxNumGovernanceKeys)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
    },
    {
      fieldName: 'Synchronizer Node Config Limits (maxNumSequencingKeys)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
    },
    {
      fieldName: 'Synchronizer Node Config Limits (maxNodeIdLength)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
    },
    {
      fieldName: 'Synchronizer Node Config Limits (maxPubKeyLength)',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
    },
    {
      fieldName: 'Max Text Length',
      currentValue: before?.maxTextLength || '',
      newValue: after?.maxTextLength || '',
    },

    ...buildSynchronizerMap(before?.decentralizedSynchronizer, after?.decentralizedSynchronizer),

    {
      fieldName: 'Decentralized Synchronizer (Last Synchronizer ID)',
      currentValue: before?.decentralizedSynchronizer.lastSynchronizerId || '',
      newValue: after?.decentralizedSynchronizer.lastSynchronizerId || '',
      isId: true,
    },
    {
      fieldName: 'Decentralized Synchronizer (Active Synchronizer ID)',
      currentValue: before?.decentralizedSynchronizer.activeSynchronizerId || '',
      newValue: after?.decentralizedSynchronizer.activeSynchronizerId || '',
      isId: true,
    },
    {
      fieldName: 'Next Scheduled Synchronizer Upgrade Time',
      currentValue: before?.nextScheduledSynchronizerUpgrade?.time || '',
      newValue: after?.nextScheduledSynchronizerUpgrade?.time || '',
    },
    {
      fieldName: 'Next Scheduled Synchronizer Upgrade Migration ID',
      currentValue: before?.nextScheduledSynchronizerUpgrade?.migrationId || '',
      newValue: after?.nextScheduledSynchronizerUpgrade?.migrationId || '',
    },
  ] as ConfigChange[];

  return changes.filter(c => c.currentValue !== c.newValue);
}
