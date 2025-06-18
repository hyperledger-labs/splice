// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { DsoDecentralizedSynchronizerConfig } from '@daml.js/splice-dso-governance/lib/Splice/DSO/DecentralizedSynchronizer/module';
import { ConfigChange } from '../components/governance/VoteRequestDetailsContent';
import { Optional } from '@daml/types';

export function buildSynchronizerMap(
  baseConfig: DsoDecentralizedSynchronizerConfig | undefined,
  currentConfig: DsoDecentralizedSynchronizerConfig | undefined
): ConfigChange[][] {
  const baseSynchronizers = baseConfig?.synchronizers.entriesArray();
  const currentSynchronizers = currentConfig?.synchronizers.entriesArray();

  const res = baseSynchronizers?.map(base => {
    return [
      {
        fieldName: `Decentralized Synchronizer`,
        currentValue: base[0] || '',
        newValue: base[0] || '', // TODO: confirm that this doesn't change here
        isId: true,
      },
      {
        fieldName: `Decentralized Synchronizer (state)`,
        currentValue: base[1].state || '',
        newValue: currentSynchronizers?.find(c => c[0] === base[0])?.[1].state || '',
      },
      {
        fieldName: `Decentralized Synchronizer (cometBftGenesisJson)`,
        currentValue: base[1].cometBftGenesisJson || '',
        newValue: currentSynchronizers?.find(c => c[0] === base[0])?.[1].cometBftGenesisJson || '',
      },
      {
        fieldName: `Decentralized Synchronizer (ACS Commitment Reconciliation Interval)`,
        currentValue: base[1].acsCommitmentReconciliationInterval || '',
        newValue:
          currentSynchronizers?.find(c => c[0] === base[0])?.[1]
            .acsCommitmentReconciliationInterval || '',
      },
    ];
  });

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

    ...buildSynchronizerMap(
      before?.decentralizedSynchronizer,
      after?.decentralizedSynchronizer
    ).flat(),

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
      // TODO: format this field in UTC
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
