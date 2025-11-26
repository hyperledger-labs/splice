import {
  ActionRequiringConfirmation,
  DsoRulesConfig,
  SynchronizerUpgradeSchedule,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

export function getDsoRulesConfig(
  numMemberTrafficContractsThreshold: string,
  acsCommitmentReconciliationInterval: string,
  nextScheduledSynchronizerUpgrade?: SynchronizerUpgradeSchedule
): DsoRulesConfig {
  return {
    numMemberTrafficContractsThreshold: numMemberTrafficContractsThreshold,
    dsoDelegateInactiveTimeout: {
      microseconds: '70000000',
    },
    svOnboardingRequestTimeout: {
      microseconds: '3600000000',
    },
    nextScheduledSynchronizerUpgrade: nextScheduledSynchronizerUpgrade
      ? nextScheduledSynchronizerUpgrade
      : null,
    actionConfirmationTimeout: {
      microseconds: '3600000000',
    },
    maxTextLength: '1024',
    voteRequestTimeout: {
      microseconds: '604800000000',
    },
    decentralizedSynchronizer: {
      synchronizers: [
        [
          'global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37',
          {
            state: 'DS_Operational',
            cometBftGenesisJson:
              'TODO(DACH-NY/canton-network-node#4900): share CometBFT genesis.json of sv1 via DsoRules config.',
            acsCommitmentReconciliationInterval: acsCommitmentReconciliationInterval,
          },
        ],
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      ] as any,
      lastSynchronizerId:
        'global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37',
      activeSynchronizerId:
        'global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37',
    },
    numUnclaimedRewardsThreshold: '10',
    svOnboardingConfirmedTimeout: {
      microseconds: '3600000000',
    },
    synchronizerNodeConfigLimits: {
      cometBft: {
        maxNumSequencingKeys: '2',
        maxNodeIdLength: '50',
        maxNumCometBftNodes: '2',
        maxPubKeyLength: '256',
        maxNumGovernanceKeys: '2',
      },
    },
    voteCooldownTime: {
      microseconds: '60000000', // 1 minute
    },
    voteExecutionInstructionTimeout: {
      microseconds: '86400000000', // 1 day
    },
  };
}

export function getDsoSetConfigAction(
  acsCommitmentReconciliationInterval: { new: string; base?: string },
  numMemberTrafficContractsThreshold: { new: string; base?: string } = { new: '5', base: '5' }
): ActionRequiringConfirmation {
  return {
    tag: 'ARC_DsoRules',
    value: {
      dsoAction: {
        tag: 'SRARC_SetConfig',
        value: {
          newConfig: getDsoRulesConfig(
            numMemberTrafficContractsThreshold.new,
            acsCommitmentReconciliationInterval.new
          ),
          baseConfig:
            acsCommitmentReconciliationInterval.base && numMemberTrafficContractsThreshold.base
              ? getDsoRulesConfig(
                  numMemberTrafficContractsThreshold.base,
                  acsCommitmentReconciliationInterval.base
                )
              : null,
        },
      },
    },
  };
}

export function getDsoSvOffboardingAction(sv: string): ActionRequiringConfirmation {
  return {
    tag: 'ARC_DsoRules',
    value: {
      dsoAction: {
        tag: 'SRARC_OffboardSv',
        value: {
          sv: sv,
        },
      },
    },
  };
}

export function getUpdateSvRewardWeightAction(sv: string): ActionRequiringConfirmation {
  return {
    tag: 'ARC_DsoRules',
    value: {
      dsoAction: {
        tag: 'SRARC_UpdateSvRewardWeight',
        value: {
          svParty: sv,
          newRewardWeight: '1000',
        },
      },
    },
  };
}

export function getExpectedDsoRulesConfigDiffsHTML(
  originalAcsCommitmentReconciliationInterval: string,
  replacementAcsCommitmentReconciliationInterval: string,
  isVoteResult: boolean = false
): string {
  const voteRequestDiff = `
<div
  class="jsondiffpatch-delta jsondiffpatch-node jsondiffpatch-child-node-type-object"><ul
    class="jsondiffpatch-node jsondiffpatch-node-type-object"><li
      data-key="actionConfirmationTimeout" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">actionConfirmationTimeout</div><div
        class="jsondiffpatch-value"><pre>{
  "microseconds": "3600000000"
}</pre></div></li><li data-key="decentralizedSynchronizer"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div
        class="jsondiffpatch-property-name">decentralizedSynchronizer</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-object"><li
      data-key="activeSynchronizerId" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">activeSynchronizerId</div><div
        class="jsondiffpatch-value"><pre>"global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"</pre></div></li><li
      data-key="lastSynchronizerId" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">lastSynchronizerId</div><div
        class="jsondiffpatch-value"><pre>"global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"</pre></div></li><li
      data-key="synchronizers"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div
        class="jsondiffpatch-property-name">synchronizers</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-object"><li
      data-key="0" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">0</div><div
        class="jsondiffpatch-value"><pre>[
  "global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37",
  {
    "state": "DS_Operational",
    "cometBftGenesisJson": "TODO(DACH-NY/canton-network-node#4900): share CometBFT genesis.json of sv1 via DsoRules config.",
    "acsCommitmentReconciliationInterval": "1600"
  }
]</pre></div></li><li data-key="_kvs"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-array"><div
        class="jsondiffpatch-property-name">_kvs</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-array"><li
      data-key="0"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-array"><div
        class="jsondiffpatch-property-name">0</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-array"><li
      data-key="1"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div
        class="jsondiffpatch-property-name">1</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-object"><li
      data-key="acsCommitmentReconciliationInterval"
      class="jsondiffpatch-modified"><div
        class="jsondiffpatch-property-name">acsCommitmentReconciliationInterval</div><div
        class="jsondiffpatch-value jsondiffpatch-left-value"><pre>"1600"</pre></div><div
        class="jsondiffpatch-value jsondiffpatch-right-value"><pre>"2100"</pre></div></li></ul></li></ul></li></ul></li><li
    data-key="_values"
    class="jsondiffpatch-node jsondiffpatch-child-node-type-array"><div
      class="jsondiffpatch-property-name">_values</div><ul
      class="jsondiffpatch-node jsondiffpatch-node-type-array"><li
    data-key="0"
    class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div
      class="jsondiffpatch-property-name">0</div><ul
      class="jsondiffpatch-node jsondiffpatch-node-type-object"><li
    data-key="acsCommitmentReconciliationInterval"
    class="jsondiffpatch-modified"><div
      class="jsondiffpatch-property-name">acsCommitmentReconciliationInterval</div><div
      class="jsondiffpatch-value jsondiffpatch-left-value"><pre>"${originalAcsCommitmentReconciliationInterval}"</pre></div><div
      class="jsondiffpatch-value jsondiffpatch-right-value"><pre>"${replacementAcsCommitmentReconciliationInterval}"</pre></div></li></ul></li></ul></li></ul></li></ul></li><li
    data-key="dsoDelegateInactiveTimeout" class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">dsoDelegateInactiveTimeout</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "70000000"
}</pre></div></li><li data-key="maxTextLength"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">maxTextLength</div><div
      class="jsondiffpatch-value"><pre>"1024"</pre></div></li><li
    data-key="nextScheduledSynchronizerUpgrade"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">nextScheduledSynchronizerUpgrade</div><div
      class="jsondiffpatch-value"><pre>null</pre></div></li><li
    data-key="numMemberTrafficContractsThreshold"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">numMemberTrafficContractsThreshold</div><div
      class="jsondiffpatch-value"><pre>"5"</pre></div></li><li
    data-key="numUnclaimedRewardsThreshold" class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">numUnclaimedRewardsThreshold</div><div
      class="jsondiffpatch-value"><pre>"10"</pre></div></li><li
    data-key="svOnboardingConfirmedTimeout" class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">svOnboardingConfirmedTimeout</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "3600000000"
}</pre></div></li><li data-key="svOnboardingRequestTimeout"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">svOnboardingRequestTimeout</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "3600000000"
}</pre></div></li><li data-key="synchronizerNodeConfigLimits"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">synchronizerNodeConfigLimits</div><div
      class="jsondiffpatch-value"><pre>{
  "cometBft": {
    "maxNumCometBftNodes": "2",
    "maxNumGovernanceKeys": "2",
    "maxNumSequencingKeys": "2",
    "maxNodeIdLength": "50",
    "maxPubKeyLength": "256"
  }
}</pre></div></li><li data-key="voteCooldownTime"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">voteCooldownTime</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "60000000"
}</pre></div></li><li data-key="voteRequestTimeout"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">voteRequestTimeout</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "604800000000"
}</pre></div></li></ul></div>
`;

  const voteResultDiff = `
<div
  class="jsondiffpatch-delta jsondiffpatch-node jsondiffpatch-child-node-type-object"><ul
    class="jsondiffpatch-node jsondiffpatch-node-type-object"><li
      data-key="actionConfirmationTimeout" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">actionConfirmationTimeout</div><div
        class="jsondiffpatch-value"><pre>{
  "microseconds": "3600000000"
}</pre></div></li><li data-key="decentralizedSynchronizer"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div
        class="jsondiffpatch-property-name">decentralizedSynchronizer</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-object"><li
      data-key="activeSynchronizerId" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">activeSynchronizerId</div><div
        class="jsondiffpatch-value"><pre>"global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"</pre></div></li><li
      data-key="lastSynchronizerId" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">lastSynchronizerId</div><div
        class="jsondiffpatch-value"><pre>"global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"</pre></div></li><li
      data-key="synchronizers"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-array"><div
        class="jsondiffpatch-property-name">synchronizers</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-array"><li
      data-key="0"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-array"><div
        class="jsondiffpatch-property-name">0</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-array"><li
      data-key="0" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">0</div><div
        class="jsondiffpatch-value"><pre>"global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"</pre></div></li><li
      data-key="1"
      class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div
        class="jsondiffpatch-property-name">1</div><ul
        class="jsondiffpatch-node jsondiffpatch-node-type-object"><li
      data-key="acsCommitmentReconciliationInterval"
      class="jsondiffpatch-modified"><div
        class="jsondiffpatch-property-name">acsCommitmentReconciliationInterval</div><div
        class="jsondiffpatch-value jsondiffpatch-left-value"><pre>"${originalAcsCommitmentReconciliationInterval}"</pre></div><div
        class="jsondiffpatch-value jsondiffpatch-right-value"><pre>"${replacementAcsCommitmentReconciliationInterval}"</pre></div></li><li
      data-key="cometBftGenesisJson" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">cometBftGenesisJson</div><div
        class="jsondiffpatch-value"><pre>"TODO(DACH-NY/canton-network-node#4900): share CometBFT genesis.json of sv1 via DsoRules config."</pre></div></li><li
      data-key="state" class="jsondiffpatch-unchanged"><div
        class="jsondiffpatch-property-name">state</div><div
        class="jsondiffpatch-value"><pre>"DS_Operational"</pre></div></li></ul></li></ul></li></ul></li></ul></li><li
    data-key="dsoDelegateInactiveTimeout" class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">dsoDelegateInactiveTimeout</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "70000000"
}</pre></div></li><li data-key="maxTextLength"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">maxTextLength</div><div
      class="jsondiffpatch-value"><pre>"1024"</pre></div></li><li
    data-key="nextScheduledSynchronizerUpgrade"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">nextScheduledSynchronizerUpgrade</div><div
      class="jsondiffpatch-value"><pre>null</pre></div></li><li
    data-key="numMemberTrafficContractsThreshold"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">numMemberTrafficContractsThreshold</div><div
      class="jsondiffpatch-value"><pre>"5"</pre></div></li><li
    data-key="numUnclaimedRewardsThreshold" class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">numUnclaimedRewardsThreshold</div><div
      class="jsondiffpatch-value"><pre>"10"</pre></div></li><li
    data-key="svOnboardingConfirmedTimeout" class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">svOnboardingConfirmedTimeout</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "3600000000"
}</pre></div></li><li data-key="svOnboardingRequestTimeout"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">svOnboardingRequestTimeout</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "3600000000"
}</pre></div></li><li data-key="synchronizerNodeConfigLimits"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">synchronizerNodeConfigLimits</div><div
      class="jsondiffpatch-value"><pre>{
  "cometBft": {
    "maxNumCometBftNodes": "2",
    "maxNumGovernanceKeys": "2",
    "maxNumSequencingKeys": "2",
    "maxNodeIdLength": "50",
    "maxPubKeyLength": "256"
  }
}</pre></div></li><li data-key="voteCooldownTime"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">voteCooldownTime</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "60000000"
}</pre></div></li><li data-key="voteRequestTimeout"
    class="jsondiffpatch-unchanged"><div
      class="jsondiffpatch-property-name">voteRequestTimeout</div><div
      class="jsondiffpatch-value"><pre>{
  "microseconds": "604800000000"
}</pre></div></li></ul></div>
`;

  return isVoteResult ? voteResultDiff : voteRequestDiff;
}
