// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as jtv from '@mojotech/json-type-validation';
import { dsoInfo, getAmuletConfig, getDsoConfig } from 'common-test-utils';
import {
  ListDsoRulesVoteRequestsResponse,
  ListDsoRulesVoteResultsResponse,
  ListVoteRequestByTrackingCidResponse,
  LookupDsoRulesVoteRequestResponse,
} from 'sv-openapi';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

// Static constants for mock values
export const voteRequests: ListDsoRulesVoteRequestsResponse = {
  dso_rules_vote_requests: [
    {
      template_id:
        '2790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DsoRules:VoteRequest',
      contract_id:
        '10f1a2cbcd5a2dc9ad2fb9d17fec183d75de19ca91f623cbd2eaaf634e8d7cb4b5ca101220b5c5c20442f608e151ca702e0c4f51341a338c5979c0547dfcc80f911061ca99',
      payload: {
        dso: 'DSO::1220ebe7643fe0617f6f8e1d147137a3b174b350adf0ac2280f967c9abb712c81afb',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: true,
              reason: {
                url: '',
                body: 'I accept, as I requested the vote.',
              },
            },
          ],
        ],
        voteBefore: '2038-09-11T10:27:52.300591Z',
        requester: 'Digital-Asset-2',
        reason: {
          url: '',
          body: 'df',
        },
        trackingCid: null,
        action: getAmuletRulesAction(
          'CRARC_AddFutureAmuletConfigSchedule',
          '2042-09-11T10:27:00Z',
          '222.2'
        ),
      },
      created_event_blob:
        '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',
      created_at: '2024-09-11T10:28:09.304591Z',
    },
    {
      template_id:
        '2790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DsoRules:VoteRequest',
      contract_id:
        '20f1a2cbcd5a2dc9ad2fb9d17fec183d75de19ca91f623cbd2eaaf634e8d7cb4b5ca101220b5c5c20442f608e151ca702e0c4f51341a338c5979c0547dfcc80f911061ca99',
      payload: {
        dso: 'DSO::1220ebe7643fe0617f6f8e1d147137a3b174b350adf0ac2280f967c9abb712c81afb',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: true,
              reason: {
                url: '',
                body: 'I accept, as I requested the vote.',
              },
            },
          ],
        ],
        voteBefore: '2028-09-11T10:27:52.300591Z',
        requester: 'Digital-Asset-2',
        reason: {
          url: '',
          body: 'df',
        },
        trackingCid: null,
        action: getDsoAction('2100'),
      },
      created_event_blob:
        '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',
      created_at: '2024-09-11T10:28:09.304591Z',
    },
    {
      template_id:
        '2790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DsoRules:VoteRequest',
      contract_id:
        '20f1a2cbcd5a2dc9ad2fb9d17fec183d75de19ca91f623cbd2eaaf634e8d7cb4b5ca101220b5c5c20442f608e151ca702e0c4f51341a338c5979c0547dfcc80f911061ca99',
      payload: {
        dso: 'DSO::1220ebe7643fe0617f6f8e1d147137a3b174b350adf0ac2280f967c9abb712c81afb',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: true,
              reason: {
                url: '',
                body: 'I accept, as I requested the vote.',
              },
            },
          ],
        ],
        voteBefore: '2048-09-11T10:27:52.300591Z',
        requester: 'Digital-Asset-2',
        reason: {
          url: '',
          body: 'df',
        },
        trackingCid: null,
        action: getDsoAction('400'),
      },
      created_event_blob:
        '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',
      created_at: '2024-02-11T10:28:09.304591Z',
    },
  ],
};

export const voteRequest: ListVoteRequestByTrackingCidResponse = {
  vote_requests: voteRequests.dso_rules_vote_requests,
};

export const voteRequestsAddFutureConfig: LookupDsoRulesVoteRequestResponse = {
  dso_rules_vote_request: voteRequests.dso_rules_vote_requests[0],
};

export const voteRequestsSetConfig: LookupDsoRulesVoteRequestResponse = {
  dso_rules_vote_request: voteRequests.dso_rules_vote_requests[1],
};

export const voteResultsAmuletRules: ListDsoRulesVoteResultsResponse = {
  dso_rules_vote_results: [
    {
      request: {
        dso: 'DSO::122013fe9b84dfc756163484f071da40f5605c2ab9d93eb647052c15e360acce1347',
        requester: 'Digital-Asset-2',
        action: getAmuletRulesAction(
          'CRARC_AddFutureAmuletConfigSchedule',
          '2024-03-15T08:35:00Z',
          '4815162342'
        ),
        reason: {
          url: '',
          body: 'ads',
        },
        voteBefore: '2024-04-19T08:25:11.839403Z',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: true,
              reason: {
                url: '',
                body: 'I accept, as I requested the vote.',
              },
            },
          ],
        ],
        trackingCid: null,
      },
      completedAt: '2024-04-20T08:21:26.130819Z',
      offboardedVoters: [],
      abstainingSvs: [],
      outcome: {
        tag: 'VRO_Accepted',
        value: {
          effectiveAt: '2024-04-20T08:30:00Z',
        },
      },
    },
    {
      request: {
        dso: 'DSO::122013fe9b84dfc756163484f071da40f5605c2ab9d93eb647052c15e360acce1347',
        requester: 'Digital-Asset-2',
        action: getAmuletRulesAction(
          'CRARC_AddFutureAmuletConfigSchedule',
          '2024-03-15T08:35:00Z',
          '4815162342'
        ),
        reason: {
          url: '',
          body: 'ads',
        },
        voteBefore: '2024-04-19T08:15:11.839403Z',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: false,
              reason: {
                url: '',
                body: 'I refuse.',
              },
            },
          ],
        ],
        trackingCid: null,
      },
      completedAt: '2024-04-20T08:21:26.130819Z',
      offboardedVoters: [],
      abstainingSvs: [],
      outcome: {
        tag: 'VRO_Rejected',
        value: {
          effectiveAt: '2024-04-20T08:30:00Z',
        },
      },
    },
    {
      request: {
        dso: 'DSO::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: true,
              reason: {
                url: '',
                body: 'I accept, as I requested the vote.',
              },
            },
          ],
        ],
        voteBefore: '2041-09-10T13:53:11.947419Z',
        requester: 'Digital-Asset-2',
        reason: {
          url: '',
          body: 'asdf',
        },
        trackingCid: null,
        action: getAmuletRulesAction(
          'CRARC_AddFutureAmuletConfigSchedule',
          '2042-09-20T17:53:00Z',
          '1.03'
        ),
      },
      completedAt: '2024-09-10T13:53:37.031740Z',
      abstainingSvs: [],
      outcome: {
        tag: 'VRO_Accepted',
        value: {
          effectiveAt: '2042-09-20T17:53:00Z',
        },
      },
      offboardedVoters: [],
    },
  ],
};

export const voteResultsDsoRules: ListDsoRulesVoteResultsResponse = {
  dso_rules_vote_results: [
    {
      request: {
        dso: 'DSO::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c39',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: true,
              reason: {
                url: '',
                body: 'I accept, as I requested the vote.',
              },
            },
          ],
        ],
        voteBefore: '2024-10-01T20:10:01.157168Z',
        requester: 'Digital-Asset-2',
        reason: {
          url: '',
          body: 'd',
        },
        trackingCid: null,
        action: getDsoAction('2200'),
      },
      completedAt: '2024-10-01T22:10:01.253341Z',
      abstainingSvs: [],
      outcome: {
        tag: 'VRO_Accepted',
        value: {
          effectiveAt: '2024-10-01T22:10:01.253341Z',
        },
      },
      offboardedVoters: [],
    },

    {
      request: {
        dso: 'DSO::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: true,
              reason: {
                url: '',
                body: 'I accept, as I requested the vote.',
              },
            },
          ],
        ],
        voteBefore: '2024-09-09T20:10:01.157168Z',
        requester: 'Digital-Asset-2',
        reason: {
          url: '',
          body: 'd',
        },
        trackingCid: null,
        action: getDsoAction('1800'),
      },
      completedAt: '2024-09-10T16:10:10.253341Z',
      abstainingSvs: [],
      outcome: {
        tag: 'VRO_Accepted',
        value: {
          effectiveAt: '2024-09-10T16:10:10.253341Z',
        },
      },
      offboardedVoters: [],
    },
    {
      request: {
        dso: 'DSO::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c38',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da38',
              accept: false,
              reason: {
                url: '',
                body: 'I refuse.',
              },
            },
          ],
        ],
        voteBefore: '2024-08-10T20:10:01.157168Z',
        requester: 'Digital-Asset-2',
        reason: {
          url: '',
          body: 'd',
        },
        trackingCid: null,
        action: getDsoAction('2000'),
      },
      completedAt: '2024-08-11T11:10:10.253341Z',
      abstainingSvs: [],
      outcome: {
        tag: 'VRO_Rejected',
        value: {
          effectiveAt: '2024-08-11T11:10:10.253341Z',
        },
      },
      offboardedVoters: [],
    },
  ],
};

function getAmuletRulesAction(action: string, effectiveAt: string, createFee: string) {
  return {
    tag: 'ARC_AmuletRules',
    value: {
      amuletRulesAction: {
        tag: action,
        value: {
          newScheduleItem: {
            _1: effectiveAt,
            _2: getAmuletConfig(createFee),
          },
        },
      },
    },
  };
}

function getDsoAction(acsCommitmentReconciliationInterval: string) {
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

export function getExpectedAmuletRulesConfigDiffsHTML(
  originalCreateFee: string,
  replacementCreateFee: string
): string {
  return (
    '<div class="jsondiffpatch-delta jsondiffpatch-node jsondiffpatch-child-node-type-object"><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"><li data-key="decentralizedSynchronizer" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">decentralizedSynchronizer</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "requiredSynchronizers": {\n' +
    '    "map": [\n' +
    '      [\n' +
    '        "global-domain::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81",\n' +
    '        {}\n' +
    '      ]\n' +
    '    ]\n' +
    '  },\n' +
    '  "activeSynchronizer": "global-domain::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81",\n' +
    '  "fees": {\n' +
    '    "baseRateTrafficLimits": {\n' +
    '      "burstAmount": "400000",\n' +
    '      "burstWindow": {\n' +
    '        "microseconds": "1200000000"\n' +
    '      }\n' +
    '    },\n' +
    '    "extraTrafficPrice": "16.67",\n' +
    '    "readVsWriteScalingFactor": "4",\n' +
    '    "minTopupAmount": "200000"\n' +
    '  }\n' +
    '}</pre></div></li><li data-key="issuanceCurve" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">issuanceCurve</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "initialValue": {\n' +
    '    "amuletToIssuePerYear": "40000000000.0",\n' +
    '    "validatorRewardPercentage": "0.05",\n' +
    '    "appRewardPercentage": "0.15",\n' +
    '    "validatorRewardCap": "0.2",\n' +
    '    "featuredAppRewardCap": "100.0",\n' +
    '    "unfeaturedAppRewardCap": "0.6",\n' +
    '    "optValidatorFaucetCap": "2.85"\n' +
    '  },\n' +
    '  "futureValues": [\n' +
    '    {\n' +
    '      "_1": {\n' +
    '        "microseconds": "15768000000000"\n' +
    '      },\n' +
    '      "_2": {\n' +
    '        "amuletToIssuePerYear": "20000000000.0",\n' +
    '        "validatorRewardPercentage": "0.12",\n' +
    '        "appRewardPercentage": "0.4",\n' +
    '        "validatorRewardCap": "0.2",\n' +
    '        "featuredAppRewardCap": "100.0",\n' +
    '        "unfeaturedAppRewardCap": "0.6",\n' +
    '        "optValidatorFaucetCap": "2.85"\n' +
    '      }\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": {\n' +
    '        "microseconds": "47304000000000"\n' +
    '      },\n' +
    '      "_2": {\n' +
    '        "amuletToIssuePerYear": "10000000000.0",\n' +
    '        "validatorRewardPercentage": "0.18",\n' +
    '        "appRewardPercentage": "0.62",\n' +
    '        "validatorRewardCap": "0.2",\n' +
    '        "featuredAppRewardCap": "100.0",\n' +
    '        "unfeaturedAppRewardCap": "0.6",\n' +
    '        "optValidatorFaucetCap": "2.85"\n' +
    '      }\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": {\n' +
    '        "microseconds": "157680000000000"\n' +
    '      },\n' +
    '      "_2": {\n' +
    '        "amuletToIssuePerYear": "5000000000.0",\n' +
    '        "validatorRewardPercentage": "0.21",\n' +
    '        "appRewardPercentage": "0.69",\n' +
    '        "validatorRewardCap": "0.2",\n' +
    '        "featuredAppRewardCap": "100.0",\n' +
    '        "unfeaturedAppRewardCap": "0.6",\n' +
    '        "optValidatorFaucetCap": "2.85"\n' +
    '      }\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": {\n' +
    '        "microseconds": "315360000000000"\n' +
    '      },\n' +
    '      "_2": {\n' +
    '        "amuletToIssuePerYear": "2500000000.0",\n' +
    '        "validatorRewardPercentage": "0.2",\n' +
    '        "appRewardPercentage": "0.75",\n' +
    '        "validatorRewardCap": "0.2",\n' +
    '        "featuredAppRewardCap": "100.0",\n' +
    '        "unfeaturedAppRewardCap": "0.6",\n' +
    '        "optValidatorFaucetCap": "2.85"\n' +
    '      }\n' +
    '    }\n' +
    '  ]\n' +
    '}</pre></div></li><li data-key="packageConfig" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">packageConfig</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "amulet": "0.1.5",\n' +
    '  "amuletNameService": "0.1.5",\n' +
    '  "dsoGovernance": "0.1.8",\n' +
    '  "validatorLifecycle": "0.1.1",\n' +
    '  "wallet": "0.1.5",\n' +
    '  "walletPayments": "0.1.5"\n' +
    '}</pre></div></li><li data-key="tickDuration" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">tickDuration</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "microseconds": "600000000"\n' +
    `}</pre></div></li><li data-key="transferConfig" class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div class="jsondiffpatch-property-name">transferConfig</div><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"></ul></li><li data-key="createFee" class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div class="jsondiffpatch-property-name">createFee</div><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"></ul></li><li data-key="fee" class="jsondiffpatch-modified"><div class="jsondiffpatch-property-name">fee</div><div class="jsondiffpatch-value jsondiffpatch-left-value"><pre>"${originalCreateFee}"</pre></div><div class="jsondiffpatch-value jsondiffpatch-right-value"><pre>"${replacementCreateFee}"</pre></div></li></ul><li data-key="extraFeaturedAppRewardAmount" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">extraFeaturedAppRewardAmount</div><div class="jsondiffpatch-value"><pre>"1.0"</pre></div></li><li data-key="holdingFee" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">holdingFee</div><div class="jsondiffpatch-value"><pre>{\n` +
    '  "rate": "0.0000190259"\n' +
    '}</pre></div></li><li data-key="lockHolderFee" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">lockHolderFee</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "fee": "0.005"\n' +
    '}</pre></div></li><li data-key="maxNumInputs" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">maxNumInputs</div><div class="jsondiffpatch-value"><pre>"100"</pre></div></li><li data-key="maxNumLockHolders" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">maxNumLockHolders</div><div class="jsondiffpatch-value"><pre>"50"</pre></div></li><li data-key="maxNumOutputs" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">maxNumOutputs</div><div class="jsondiffpatch-value"><pre>"100"</pre></div></li><li data-key="transferFee" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">transferFee</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "initialRate": "0.01",\n' +
    '  "steps": [\n' +
    '    {\n' +
    '      "_1": "100.0",\n' +
    '      "_2": "0.001"\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": "1000.0",\n' +
    '      "_2": "0.0001"\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": "1000000.0",\n' +
    '      "_2": "0.00001"\n' +
    '    }\n' +
    '  ]\n' +
    '}</pre></div></li></div>\n'.trim()
  );
}

const result = jtv.Result.andThen(
  () => AmuletRules.decoder.run(dsoInfo.amulet_rules.contract.payload),
  DsoRules.decoder.run(dsoInfo.dso_rules.contract.payload)
);
if (!result.ok) {
  throw new Error(`Invalid DsoInfo mock: ${JSON.stringify(result.error)}`);
}

export const svPartyId = dsoInfo.sv_party_id;
