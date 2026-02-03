// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DsoRules_CloseVoteRequestResult,
  Vote,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId, emptyMap } from '@daml/types';

import { Contract } from '../../../utils';
import { AmuletPriceVote, SvVote } from '../../models';

export function myVote(requestCid: ContractId<VoteRequest>, accept: boolean): SvVote {
  return {
    reason: {
      url: '',
      body: 'A reason',
    },
    expiresAt: new Date(),
    requestCid,
    voter: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
    accept,
  };
}

export const amuletPriceVotes: AmuletPriceVote[] = [
  {
    sv: 'digital-asset-2::122073b343bd7a751da8471636096c87df6eb5de9279803c5ea272977def23a9d088',
    amuletPrice: '1.11',
    lastUpdatedAt: new Date('2025-02-12T13:50:54.176913Z'),
  },
  {
    sv: 'digital-asset-eng-2::1220a487994f51a4ea99be5d7c94662b91c2154214a312ade0d55367b884b6fd8dfc',
    amuletPrice: '1.11',
    lastUpdatedAt: new Date('2025-02-12T10:50:54.176913Z'),
  },
];

export const plannedVoteResult: DsoRules_CloseVoteRequestResult = {
  request: {
    dso: 'DSO::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
    requester: 'Digital-Asset-2',
    action: {
      tag: 'ARC_AmuletRules',
      value: {
        amuletRulesAction: {
          tag: 'CRARC_AddFutureAmuletConfigSchedule',
          value: {
            newScheduleItem: {
              _1: '2024-09-06T08:16:00Z',
              _2: {
                transferConfig: {
                  createFee: {
                    fee: '0.06',
                  },
                  holdingFee: {
                    rate: '0.0000190259',
                  },
                  transferFee: {
                    initialRate: '0.01',
                    steps: [
                      {
                        _1: '100.0',
                        _2: '0.001',
                      },
                      {
                        _1: '1000.0',
                        _2: '0.0001',
                      },
                      {
                        _1: '1000000.0',
                        _2: '0.00001',
                      },
                    ],
                  },
                  lockHolderFee: {
                    fee: '0.005',
                  },
                  extraFeaturedAppRewardAmount: '1.0',
                  maxNumInputs: '100',
                  maxNumOutputs: '100',
                  maxNumLockHolders: '50',
                },
                issuanceCurve: {
                  initialValue: {
                    amuletToIssuePerYear: '40000000000.0',
                    validatorRewardPercentage: '0.05',
                    appRewardPercentage: '0.15',
                    validatorRewardCap: '0.2',
                    featuredAppRewardCap: '100.0',
                    unfeaturedAppRewardCap: '0.6',
                    optValidatorFaucetCap: '2.85',
                    optDevelopmentFundPercentage: '0.05',
                  },
                  futureValues: [
                    {
                      _1: {
                        microseconds: '15768000000000',
                      },
                      _2: {
                        amuletToIssuePerYear: '20000000000.0',
                        validatorRewardPercentage: '0.12',
                        appRewardPercentage: '0.4',
                        validatorRewardCap: '0.2',
                        featuredAppRewardCap: '100.0',
                        unfeaturedAppRewardCap: '0.6',
                        optValidatorFaucetCap: '2.85',
                        optDevelopmentFundPercentage: '0.05',
                      },
                    },
                    {
                      _1: {
                        microseconds: '47304000000000',
                      },
                      _2: {
                        amuletToIssuePerYear: '10000000000.0',
                        validatorRewardPercentage: '0.18',
                        appRewardPercentage: '0.62',
                        validatorRewardCap: '0.2',
                        featuredAppRewardCap: '100.0',
                        unfeaturedAppRewardCap: '0.6',
                        optValidatorFaucetCap: '2.85',
                        optDevelopmentFundPercentage: '0.05',
                      },
                    },
                    {
                      _1: {
                        microseconds: '157680000000000',
                      },
                      _2: {
                        amuletToIssuePerYear: '5000000000.0',
                        validatorRewardPercentage: '0.21',
                        appRewardPercentage: '0.69',
                        validatorRewardCap: '0.2',
                        featuredAppRewardCap: '100.0',
                        unfeaturedAppRewardCap: '0.6',
                        optValidatorFaucetCap: '2.85',
                        optDevelopmentFundPercentage: '0.05',
                      },
                    },
                    {
                      _1: {
                        microseconds: '315360000000000',
                      },
                      _2: {
                        amuletToIssuePerYear: '2500000000.0',
                        validatorRewardPercentage: '0.2',
                        appRewardPercentage: '0.75',
                        validatorRewardCap: '0.2',
                        featuredAppRewardCap: '100.0',
                        unfeaturedAppRewardCap: '0.6',
                        optValidatorFaucetCap: '2.85',
                        optDevelopmentFundPercentage: '0.05',
                      },
                    },
                  ],
                },
                decentralizedSynchronizer: {
                  requiredSynchronizers: {
                    /* eslint-disable */
                    map: emptyMap<string, {}>().set(
                      'global-domain::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
                      {}
                    ),
                    /* eslint-enable */
                  },
                  activeSynchronizer:
                    'global-domain::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
                  fees: {
                    baseRateTrafficLimits: {
                      burstAmount: '400000',
                      burstWindow: {
                        microseconds: '1200000000',
                      },
                    },
                    extraTrafficPrice: '16.67',
                    readVsWriteScalingFactor: '4',
                    minTopupAmount: '200000',
                  },
                },
                tickDuration: {
                  microseconds: '600000000',
                },
                packageConfig: {
                  amulet: '0.1.5',
                  amuletNameService: '0.1.5',
                  dsoGovernance: '0.1.8',
                  validatorLifecycle: '0.1.1',
                  wallet: '0.1.5',
                  walletPayments: '0.1.5',
                },
                transferPreapprovalFee: null,
                featuredAppActivityMarkerAmount: null,
                optDevelopmentFundManager: null,
              },
            },
          },
        },
      },
    },
    reason: {
      url: '',
      body: 'Adjust fee',
    },
    targetEffectiveAt: null,
    voteBefore: '2024-09-05T08:40:55.977789Z',
    votes: emptyMap<string, Vote>().set('Digital-Asset-2', {
      sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      reason: {
        url: '',
        body: 'yes',
      },
      accept: true,
      optCastAt: null,
    }),
    trackingCid: null,
  },
  completedAt: '2024-09-05T08:17:54.588505Z',
  offboardedVoters: [],
  abstainingSvs: [],
  outcome: {
    tag: 'VRO_Accepted',
    value: {
      effectiveAt: '3024-09-06T08:16:00Z', // guaranteed to be in the future for long enough
    },
  },
};

export const executedVoteResult: DsoRules_CloseVoteRequestResult = {
  request: {
    dso: 'DSO::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
    requester: 'Digital-Asset-2',
    action: {
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_UpdateSvRewardWeight',
          value: {
            svParty:
              'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
            newRewardWeight: '200000',
          },
        },
      },
    },
    reason: {
      url: '',
      body: 'Test 2',
    },
    targetEffectiveAt: null,
    voteBefore: '2024-09-13T07:51:14.091940Z',
    votes: emptyMap<string, Vote>().set('Digital-Asset-2', {
      sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      reason: {
        url: '',
        body: 'yes',
      },
      accept: true,
      optCastAt: null,
    }),
    trackingCid: null,
  },
  completedAt: '2024-09-05T07:51:42.354519Z',
  offboardedVoters: [],
  abstainingSvs: [],
  outcome: {
    tag: 'VRO_Accepted',
    value: {
      effectiveAt: '2024-09-05T07:51:42.354519Z',
    },
  },
};

export const rejectedVoteResult: DsoRules_CloseVoteRequestResult = {
  request: {
    dso: 'DSO::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
    requester: 'Digital-Asset-2',
    action: {
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_UpdateSvRewardWeight',
          value: {
            svParty:
              'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
            newRewardWeight: '1',
          },
        },
      },
    },
    reason: {
      url: '',
      body: 'Test reject',
    },
    voteBefore: '2024-09-12T08:45:20.307127Z',
    targetEffectiveAt: null,
    votes: emptyMap<string, Vote>().set('Digital-Asset-2', {
      sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      accept: false,
      reason: {
        url: '',
        body: 'I reject, as I reconsidered.',
      },
      optCastAt: null,
    }),
    trackingCid:
      '004a89c6a34e1bc92e8b06848ed05e6d9e796ce4ffb05af0ea0cda7b9636d2d441ca101220f0547038dd55d1fb90ab911ca343480bce0e3a19d116fe67f2c2cacb0028dc48' as ContractId<VoteRequest>,
  },
  completedAt: '2024-09-05T08:31:06.736622Z',
  offboardedVoters: [],
  abstainingSvs: [],
  outcome: {
    tag: 'VRO_Rejected',
    value: {},
  },
};

export const votedRequest: Contract<VoteRequest> = {
  templateId:
    '1790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DsoRules:VoteRequest',
  contractId:
    '00bb0a31fc2054db0365c7063db93cc43cfc9fd79f70bc78daff656a9bd6f9370fca101220ad980d3af8d54ed5ca8927ee0a2eaa65a4033e63c9a646fca0f05446676748cb' as ContractId<VoteRequest>,
  payload: {
    dso: 'DSO::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
    votes: emptyMap<string, Vote>().set('Digital-Asset-2', {
      sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      reason: {
        url: '',
        body: 'yes',
      },
      accept: true,
      optCastAt: null,
    }),
    voteBefore: '2024-09-12T08:13:16.584772Z',
    requester: 'Digital-Asset-2',
    reason: {
      url: '',
      body: 'Test 3',
    },
    targetEffectiveAt: null,
    trackingCid: null,
    action: {
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_UpdateSvRewardWeight',
          value: {
            svParty:
              'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
            newRewardWeight: '300000',
          },
        },
      },
    },
  },
  createdEventBlob:
    'CgMyLjESuQYKRQC7CjH8IFTbA2XHBj25PMQ8/J/Xn3C8eNr/ZWqb1vk3D8oQEiCtmA06+NVO1cqJJ+4KLqplpAM+Y8mmRvyg8FRGZ2dIyxIVc3BsaWNlLWRzby1nb3Zlcm5hbmNlGmEKQDE3OTBhMTE0ZjgzZDVmMjkwMjYxZmFlMWU3ZTQ2ZmJhNzVhODYxYTNkZDYwM2M2YjRlZjZiNjdiNDkwNTM5NDgSBlNwbGljZRIIRHNvUnVsZXMaC1ZvdGVSZXF1ZXN0IvUDavIDCk0KSzpJRFNPOjoxMjIwNThmZDYxYTU2NGYwMzgyM2JjMWUyN2YxNmRlNTE5Y2FiY2QzNjgxZmMzMWFiNTIzZjRlZjY5OGRjNmI2YzNhYgoTChFCD0RpZ2l0YWwtQXNzZXQtMgqmAQqjAXKgAQoMQVJDX0Rzb1J1bGVzEo8BaowBCokBCoYBcoMBChpTUkFSQ19VcGRhdGVTdlJld2FyZFdlaWdodBJlamMKWQpXOlVkaWdpdGFsLWFzc2V0LTI6OjEyMjBhMTUwNGQ4NzQ1MWNlNmE0YTEzNTVkNzFjNGYyNjY3N2Q2M2RiNWU2Mjk4ZDA0YjBkMTg2NWZkOTcxNDMyYTQxCgYKBBjAzyQKFgoUahIKBAoCQgAKCgoIQgZUZXN0IDMKCwoJKUTLwa3nIQYACrcBCrQBYrEBCq4BChFCD0RpZ2l0YWwtQXNzZXQtMhKYAWqVAQpZClc6VWRpZ2l0YWwtYXNzZXQtMjo6MTIyMGExNTA0ZDg3NDUxY2U2YTRhMTM1NWQ3MWM0ZjI2Njc3ZDYzZGI1ZTYyOThkMDRiMGQxODY1ZmQ5NzE0MzJhNDEKBAoCEAEKMgowai4KBAoCQgAKJgokQiJJIGFjY2VwdCwgYXMgSSByZXF1ZXN0ZWQgdGhlIHZvdGUuCgQKAlIAKklEU086OjEyMjA1OGZkNjFhNTY0ZjAzODIzYmMxZTI3ZjE2ZGU1MTljYWJjZDM2ODFmYzMxYWI1MjNmNGVmNjk4ZGM2YjZjM2FiOTSmQN1aIQYAQioKJgokCAESIHFq7Tf1rU33ywG032I92KCzsnvbFh0BH8vZPb2kb3FfEB4=',
  createdAt: '2024-09-05T08:13:23.038772Z',
};

export const unvotedRequest: Contract<VoteRequest> = {
  templateId:
    '1790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DsoRules:VoteRequest',
  contractId:
    '11bb0a31fc2054db0365c7063db93cc43cfc9fd79f70bc78daff656a9bd6f9370fca101220ad980d3af8d54ed5ca8927ee0a2eaa65a4033e63c9a646fca0f05446676748cb' as ContractId<VoteRequest>,
  payload: {
    dso: 'DSO::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
    votes: emptyMap<string, Vote>(),
    voteBefore: '2024-09-12T08:13:16.584772Z',
    requester: 'Digital-Asset-2',
    reason: {
      url: '',
      body: 'Test 3',
    },
    targetEffectiveAt: null,
    trackingCid: null,
    action: {
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_UpdateSvRewardWeight',
          value: {
            svParty:
              'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
            newRewardWeight: '300000',
          },
        },
      },
    },
  },
  createdEventBlob:
    'CgMyLjESuQYKRQC7CjH8IFTbA2XHBj25PMQ8/J/Xn3C8eNr/ZWqb1vk3D8oQEiCtmA06+NVO1cqJJ+4KLqplpAM+Y8mmRvyg8FRGZ2dIyxIVc3BsaWNlLWRzby1nb3Zlcm5hbmNlGmEKQDE3OTBhMTE0ZjgzZDVmMjkwMjYxZmFlMWU3ZTQ2ZmJhNzVhODYxYTNkZDYwM2M2YjRlZjZiNjdiNDkwNTM5NDgSBlNwbGljZRIIRHNvUnVsZXMaC1ZvdGVSZXF1ZXN0IvUDavIDCk0KSzpJRFNPOjoxMjIwNThmZDYxYTU2NGYwMzgyM2JjMWUyN2YxNmRlNTE5Y2FiY2QzNjgxZmMzMWFiNTIzZjRlZjY5OGRjNmI2YzNhYgoTChFCD0RpZ2l0YWwtQXNzZXQtMgqmAQqjAXKgAQoMQVJDX0Rzb1J1bGVzEo8BaowBCokBCoYBcoMBChpTUkFSQ19VcGRhdGVTdlJld2FyZFdlaWdodBJlamMKWQpXOlVkaWdpdGFsLWFzc2V0LTI6OjEyMjBhMTUwNGQ4NzQ1MWNlNmE0YTEzNTVkNzFjNGYyNjY3N2Q2M2RiNWU2Mjk4ZDA0YjBkMTg2NWZkOTcxNDMyYTQxCgYKBBjAzyQKFgoUahIKBAoCQgAKCgoIQgZUZXN0IDMKCwoJKUTLwa3nIQYACrcBCrQBYrEBCq4BChFCD0RpZ2l0YWwtQXNzZXQtMhKYAWqVAQpZClc6VWRpZ2l0YWwtYXNzZXQtMjo6MTIyMGExNTA0ZDg3NDUxY2U2YTRhMTM1NWQ3MWM0ZjI2Njc3ZDYzZGI1ZTYyOThkMDRiMGQxODY1ZmQ5NzE0MzJhNDEKBAoCEAEKMgowai4KBAoCQgAKJgokQiJJIGFjY2VwdCwgYXMgSSByZXF1ZXN0ZWQgdGhlIHZvdGUuCgQKAlIAKklEU086OjEyMjA1OGZkNjFhNTY0ZjAzODIzYmMxZTI3ZjE2ZGU1MTljYWJjZDM2ODFmYzMxYWI1MjNmNGVmNjk4ZGM2YjZjM2FiOTSmQN1aIQYAQioKJgokCAESIHFq7Tf1rU33ywG032I92KCzsnvbFh0BH8vZPb2kb3FfEB4=',
  createdAt: '2024-09-05T08:13:23.038772Z',
};
