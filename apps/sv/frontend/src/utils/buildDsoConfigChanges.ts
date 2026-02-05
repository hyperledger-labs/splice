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
          label: `Decentralized synchronizer ${idx}: Synchronizer ID`,
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
          label: `Decentralized synchronizer ${idx}: CometBFT genesis (Json)`,
          currentValue: baseSynchronizer[1].cometBftGenesisJson || '',
          newValue: currentSynchronizer?.[1].cometBftGenesisJson || '',
          disabled: true,
        },
        {
          fieldName: `decentralizedSynchronizerAcsCommitmentReconciliationInterval${idx}`,
          label: `Decentralized synchronizer ${idx}: ACS commitment reconciliation interval`,
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
      fieldName: 'actionConfirmationTimeout',
      label: 'TTL for contracts representing a confirmation of an action',
      currentValue: before?.actionConfirmationTimeout.microseconds || '',
      newValue: after?.actionConfirmationTimeout.microseconds || '',
    },
    {
      fieldName: 'svOnboardingRequestTimeout',
      label: 'TTL for contracts representing an incomplete SV onboarding',
      currentValue: before?.svOnboardingRequestTimeout.microseconds || '',
      newValue: after?.svOnboardingRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'svOnboardingConfirmedTimeout',
      label: 'TTL for contracts representing confirmation for an SV to onboard',
      currentValue: before?.svOnboardingConfirmedTimeout.microseconds || '',
      newValue: after?.svOnboardingConfirmedTimeout.microseconds || '',
    },
    {
      fieldName: 'maxTextLength',
      label: 'Generic upper limit on text fields',
      currentValue: before?.maxTextLength || '',
      newValue: after?.maxTextLength || '',
    },
    {
      fieldName: 'voteRequestTimeout',
      label: 'TTL for contracts representing vote requests and votes',
      currentValue: before?.voteRequestTimeout.microseconds || '',
      newValue: after?.voteRequestTimeout.microseconds || '',
    },
    {
      fieldName: 'dsoDelegateInactiveTimeout',
      label: '(Deprecated) DSO delegate inactivity timeout',
      currentValue: before?.dsoDelegateInactiveTimeout.microseconds || '',
      newValue: after?.dsoDelegateInactiveTimeout.microseconds || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumCometBftNodes',
      label: 'SV node limits: CometBFT: Maximum number of CometBft nodes',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumCometBftNodes || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumGovernanceKeys',
      label: 'SV node limits: CometBFT: Maximum number of governance keys',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumGovernanceKeys || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNumSequencingKeys',
      label: 'SV node limits: CometBFT: Maximum number of sequencing keys',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNumSequencingKeys || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxNodeIdLength',
      label: 'SV node limits: CometBFT: Maximum node ID length',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxNodeIdLength || '',
    },
    {
      fieldName: 'synchronizerNodeConfigLimitsCometBftMaxPubKeyLength',
      label: 'SV node limits: CometBFT: Maximum public key length',
      currentValue: before?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
      newValue: after?.synchronizerNodeConfigLimits.cometBft.maxPubKeyLength || '',
    },

    ...buildSynchronizerMap(before?.decentralizedSynchronizer, after?.decentralizedSynchronizer),

    {
      fieldName: 'decentralizedSynchronizerLastSynchronizerId',
      label: 'Decentralized synchronizer: Last synchronizer ID',
      currentValue: before?.decentralizedSynchronizer.lastSynchronizerId || '',
      newValue: after?.decentralizedSynchronizer.lastSynchronizerId || '',
      isId: true,
      disabled: true,
    },
    {
      fieldName: 'decentralizedSynchronizerActiveSynchronizerId',
      label: 'Decentralized synchronizer: Active synchronizer ID',
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
      label: 'Next scheduled synchronizer upgrade migration ID',
      currentValue: before?.nextScheduledSynchronizerUpgrade?.migrationId || '',
      newValue: after?.nextScheduledSynchronizerUpgrade?.migrationId || '',
    },
    {
      fieldName: 'voteCooldownTime',
      label: 'The minimum time between two votes (or vote changes) by the same SV',
      currentValue: before?.voteCooldownTime?.microseconds || '',
      newValue: after?.voteCooldownTime?.microseconds || '',
    },
  ] as ConfigChange[];

  return showAllFields ? changes : changes.filter(c => c.currentValue !== c.newValue);
}
