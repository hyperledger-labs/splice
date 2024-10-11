// eslint-disable-next-line @typescript-eslint/explicit-module-boundary-types
export function getDsoConfig(acsCommitmentReconciliationInterval: string) {
  return {
    numMemberTrafficContractsThreshold: '5',
    dsoDelegateInactiveTimeout: {
      microseconds: '70000000',
    },
    svOnboardingRequestTimeout: {
      microseconds: '3600000000',
    },
    nextScheduledSynchronizerUpgrade: null,
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
              'TODO(#4900): share CometBFT genesis.json of sv1 via DsoRules config.',
            acsCommitmentReconciliationInterval: acsCommitmentReconciliationInterval,
          },
        ],
      ],
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
  };
}

// eslint-disable-next-line @typescript-eslint/explicit-module-boundary-types
export function getDsoAction(acsCommitmentReconciliationInterval: string) {
  return {
    tag: 'ARC_DsoRules',
    value: {
      dsoAction: {
        tag: 'SRARC_SetConfig',
        value: {
          newConfig: getDsoConfig(acsCommitmentReconciliationInterval),
        },
      },
    },
  };
}

export function getExpectedDsoRulesConfigDiffsHTML(
  originalAcsCommitmentReconciliationInterval: string,
  replacementAcsCommitmentReconciliationInterval: string
): string {
  return (
    '<div class="jsondiffpatch-delta jsondiffpatch-node jsondiffpatch-child-node-type-object"><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"><li data-key="actionConfirmationTimeout" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">actionConfirmationTimeout</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "microseconds": "3600000000"\n' +
    `}</pre></div></li><li data-key="decentralizedSynchronizer" class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div class="jsondiffpatch-property-name">decentralizedSynchronizer</div><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"></ul></li><li data-key="activeSynchronizerId" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">activeSynchronizerId</div><div class="jsondiffpatch-value"><pre>"global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"</pre></div></li><li data-key="lastSynchronizerId" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">lastSynchronizerId</div><div class="jsondiffpatch-value"><pre>"global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"</pre></div></li><li data-key="synchronizers" class="jsondiffpatch-node jsondiffpatch-child-node-type-array"><div class="jsondiffpatch-property-name">synchronizers</div><ul class="jsondiffpatch-node jsondiffpatch-node-type-array"></ul></li><li data-key="0" class="jsondiffpatch-node jsondiffpatch-child-node-type-array"><div class="jsondiffpatch-property-name">0</div><ul class="jsondiffpatch-node jsondiffpatch-node-type-array"></ul></li><li data-key="0" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">0</div><div class="jsondiffpatch-value"><pre>"global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"</pre></div></li><li data-key="1" class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div class="jsondiffpatch-property-name">1</div><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"></ul></li><li data-key="acsCommitmentReconciliationInterval" class="jsondiffpatch-modified"><div class="jsondiffpatch-property-name">acsCommitmentReconciliationInterval</div><div class="jsondiffpatch-value jsondiffpatch-left-value"><pre>"${originalAcsCommitmentReconciliationInterval}"</pre></div><div class="jsondiffpatch-value jsondiffpatch-right-value"><pre>"${replacementAcsCommitmentReconciliationInterval}"</pre></div></li><li data-key="cometBftGenesisJson" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">cometBftGenesisJson</div><div class="jsondiffpatch-value"><pre>"TODO(#4900): share CometBFT genesis.json of sv1 via DsoRules config."</pre></div></li><li data-key="state" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">state</div><div class="jsondiffpatch-value"><pre>"DS_Operational"</pre></div></li></ul><li data-key="dsoDelegateInactiveTimeout" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">dsoDelegateInactiveTimeout</div><div class="jsondiffpatch-value"><pre>{\n` +
    '  "microseconds": "70000000"\n' +
    '}</pre></div></li><li data-key="maxTextLength" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">maxTextLength</div><div class="jsondiffpatch-value"><pre>"1024"</pre></div></li><li data-key="nextScheduledSynchronizerUpgrade" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">nextScheduledSynchronizerUpgrade</div><div class="jsondiffpatch-value"><pre>null</pre></div></li><li data-key="numMemberTrafficContractsThreshold" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">numMemberTrafficContractsThreshold</div><div class="jsondiffpatch-value"><pre>"5"</pre></div></li><li data-key="numUnclaimedRewardsThreshold" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">numUnclaimedRewardsThreshold</div><div class="jsondiffpatch-value"><pre>"10"</pre></div></li><li data-key="svOnboardingConfirmedTimeout" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">svOnboardingConfirmedTimeout</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "microseconds": "3600000000"\n' +
    '}</pre></div></li><li data-key="svOnboardingRequestTimeout" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">svOnboardingRequestTimeout</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "microseconds": "3600000000"\n' +
    '}</pre></div></li><li data-key="synchronizerNodeConfigLimits" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">synchronizerNodeConfigLimits</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "cometBft": {\n' +
    '    "maxNumCometBftNodes": "2",\n' +
    '    "maxNumGovernanceKeys": "2",\n' +
    '    "maxNumSequencingKeys": "2",\n' +
    '    "maxNodeIdLength": "50",\n' +
    '    "maxPubKeyLength": "256"\n' +
    '  }\n' +
    '}</pre></div></li><li data-key="voteRequestTimeout" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">voteRequestTimeout</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "microseconds": "604800000000"\n' +
    '}</pre></div></li></div>'.trim()
  );
}
