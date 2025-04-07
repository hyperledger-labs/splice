// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as jtv from '@mojotech/json-type-validation';
import {
  getDsoAction,
  getAmuletRulesAction,
} from '@lfdecentralizedtrust/splice-common-test-handlers';
import { dsoInfo } from '@lfdecentralizedtrust/splice-common-test-handlers';
import {
  ListDsoRulesVoteRequestsResponse,
  ListDsoRulesVoteResultsResponse,
  ListVoteRequestByTrackingCidResponse,
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
        '10f1a2cbcd5a2dc9ad2fb9d17fec183d75de19ca91f623cbd2eaaf634e8d7cb4b5ca101220b5c5c20442f608e151ca702e0c4f51341a338c5979c0547dfcc80f911061ca91',
      payload: {
        dso: 'DSO::1220ebe7643fe0617f6f8e1d147137a3b174b350adf0ac2280f967c9abb712c81afb',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da31',
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
        '20f1a2cbcd5a2dc9ad2fb9d17fec183d75de19ca91f623cbd2eaaf634e8d7cb4b5ca101220b5c5c20442f608e151ca702e0c4f51341a338c5979c0547dfcc80f911061ca92',
      payload: {
        dso: 'DSO::1220ebe7643fe0617f6f8e1d147137a3b174b350adf0ac2280f967c9abb712c81afb',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da32',
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
        '20f1a2cbcd5a2dc9ad2fb9d17fec183d75de19ca91f623cbd2eaaf634e8d7cb4b5ca101220b5c5c20442f608e151ca702e0c4f51341a338c5979c0547dfcc80f911061ca93',
      payload: {
        dso: 'DSO::1220ebe7643fe0617f6f8e1d147137a3b174b350adf0ac2280f967c9abb712c81afb',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da33',
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
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da34',
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
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da35',
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
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da36',
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
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da37',
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
              sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da39',
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

const result = jtv.Result.andThen(
  () => AmuletRules.decoder.run(dsoInfo.amulet_rules.contract.payload),
  DsoRules.decoder.run(dsoInfo.dso_rules.contract.payload)
);
if (!result.ok) {
  throw new Error(`Invalid DsoInfo mock: ${JSON.stringify(result.error)}`);
}

export const svPartyId = dsoInfo.sv_party_id;
