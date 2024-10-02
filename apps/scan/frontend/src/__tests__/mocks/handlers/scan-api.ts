// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { validatorLicensesHandler, dsoInfoHandler } from 'common-test-utils';
import { RestHandler, rest } from 'msw';
import {
  ErrorResponse,
  GetAmuletRulesResponse,
  GetRewardsCollectedResponse,
  GetRoundOfLatestDataResponse,
  GetDsoPartyIdResponse,
  GetTotalAmuletBalanceResponse,
  ListActivityResponse,
  LookupEntryByPartyResponse,
} from 'scan-openapi';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules/module';
import damlTypes from '@daml/types';

import { config } from '../../setup/config';

const amuletNameServiceAcronym = config.spliceInstanceNames.nameServiceNameAcronym;

export const buildScanMock = (scanUrl: string): RestHandler[] => [
  dsoInfoHandler(scanUrl),
  rest.get(`${scanUrl}/v0/dso-party-id`, (_, res, ctx) => {
    return res(
      ctx.json<GetDsoPartyIdResponse>({
        dso_party_id: 'DSO::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
      })
    );
  }),
  rest.post(`${scanUrl}/v0/activities`, (_, res, ctx) => {
    return res(
      ctx.json<ListActivityResponse>({
        activities: [
          {
            activity_type: 'transfer',
            event_id: '#1220beadd2f791e69719e8ee03a6dd6e3b9c2b9196b8b679a190d203fc2c8a6a4bff:5',
            offset: '00000000000000002f',
            date: new Date(),
            domain_id:
              'global-domain::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
            amulet_price: '1.0000000000',
            round: 1,
            transfer: {
              provider:
                'alice__validator__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7',
              sender: {
                party:
                  'charlie__wallet__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7',
                input_amulet_amount: '5.0000000000',
                input_app_reward_amount: '0.0000000000',
                input_validator_reward_amount: '0.0000000000',
                input_sv_reward_amount: '0.0000000000',
                sender_change_fee: '0.0300000000',
                sender_change_amount: '3.8950000000',
                sender_fee: '0.0000000000',
                holding_fees: '0.0000000000',
              },
              balance_changes: [
                {
                  party:
                    'charlie__wallet__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7',
                  change_to_holding_fees_rate: '0.0000000000',
                  change_to_initial_amount_as_of_round_zero: '1.1050000000',
                },
              ],
              receivers: [],
            },
          },
        ],
      })
    );
  }),
  rest.get(`${scanUrl}/v0/round-of-latest-data`, (_, res, ctx) => {
    return res(ctx.json<GetRoundOfLatestDataResponse>({ round: 1, effectiveAt: new Date() }));
  }),
  rest.get(`${scanUrl}/v0/rewards-collected`, (_, res, ctx) => {
    return res(ctx.json<GetRewardsCollectedResponse>({ amount: '0.0' }));
  }),
  rest.get(`${scanUrl}/v0/total-amulet-balance`, (_, res, ctx) => {
    return res(ctx.json<GetTotalAmuletBalanceResponse>({ total_balance: '66605.2180742781' }));
  }),
  rest.post(`${scanUrl}/v0/amulet-rules`, (_, res, ctx) => {
    return res(
      ctx.json<GetAmuletRulesResponse>({
        amulet_rules_update: {
          contract: {
            template_id:
              'f4693252b3cab434649f66f5fd309ae98ca01512b10b482086aa8ff529ca83e3:Splice.Amulet:AmuletRules',
            contract_id:
              '00ed7531fa0fb6a06f0d0f1ea8a31867704da8a6c341e7262894c5d0e15312aca6ca0212200412a9e6c1b9bff1449205e02c88596bad60b8eb8d14bee48f26509f6531d4db',
            payload: AmuletRules.encode({
              dso: 'DSO::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
              configSchedule: {
                initialValue: {
                  transferConfig: {
                    holdingFee: {
                      rate: '0.0000048225',
                    },
                    maxNumInputs: '100',
                    lockHolderFee: {
                      fee: '0.005',
                    },
                    createFee: {
                      fee: '0.03',
                    },
                    extraFeaturedAppRewardAmount: '1.0',
                    maxNumLockHolders: '50',
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
                    maxNumOutputs: '100',
                  },
                  issuanceCurve: {
                    initialValue: {
                      validatorRewardPercentage: '0.5',
                      amuletToIssuePerYear: '40000000000.0',
                      unfeaturedAppRewardCap: '0.6',
                      appRewardPercentage: '0.15',
                      featuredAppRewardCap: '100.0',
                      validatorRewardCap: '0.2',
                      optValidatorFaucetCap: '2.85',
                    },
                    futureValues: [
                      {
                        _1: {
                          microseconds: '15768000000000',
                        },
                        _2: {
                          validatorRewardPercentage: '0.12',
                          amuletToIssuePerYear: '20000000000.0',
                          unfeaturedAppRewardCap: '0.6',
                          appRewardPercentage: '0.4',
                          featuredAppRewardCap: '100.0',
                          validatorRewardCap: '0.2',
                          optValidatorFaucetCap: '2.85',
                        },
                      },
                      {
                        _1: {
                          microseconds: '47304000000000',
                        },
                        _2: {
                          validatorRewardPercentage: '0.18',
                          amuletToIssuePerYear: '10000000000.0',
                          unfeaturedAppRewardCap: '0.6',
                          appRewardPercentage: '0.62',
                          featuredAppRewardCap: '100.0',
                          validatorRewardCap: '0.2',
                          optValidatorFaucetCap: '2.85',
                        },
                      },
                      {
                        _1: {
                          microseconds: '157680000000000',
                        },
                        _2: {
                          validatorRewardPercentage: '0.21',
                          amuletToIssuePerYear: '5000000000.0',
                          unfeaturedAppRewardCap: '0.6',
                          appRewardPercentage: '0.69',
                          featuredAppRewardCap: '100.0',
                          validatorRewardCap: '0.2',
                          optValidatorFaucetCap: '2.85',
                        },
                      },
                      {
                        _1: {
                          microseconds: '315360000000000',
                        },
                        _2: {
                          validatorRewardPercentage: '0.2',
                          amuletToIssuePerYear: '2500000000.0',
                          unfeaturedAppRewardCap: '0.6',
                          appRewardPercentage: '0.75',
                          featuredAppRewardCap: '100.0',
                          validatorRewardCap: '0.2',
                          optValidatorFaucetCap: '2.85',
                        },
                      },
                    ],
                  },

                  decentralizedSynchronizer: {
                    requiredSynchronizers: {
                      map: damlTypes
                        .emptyMap<string, object>()
                        .set(
                          'global-domain::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
                          {}
                        ),
                    },
                    activeSynchronizer:
                      'global-domain::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
                    fees: {
                      baseRateTrafficLimits: {
                        burstAmount: '2000000',
                        burstWindow: {
                          microseconds: '600000000',
                        },
                      },
                      extraTrafficPrice: '1.0',
                      readVsWriteScalingFactor: '4',
                      minTopupAmount: '1000',
                    },
                  },
                  tickDuration: {
                    microseconds: '600000000',
                  },
                  packageConfig: {
                    amulet: '0.1.0',
                    amuletNameService: '0.1.0',
                    dsoGovernance: '0.1.0',
                    validatorLifecycle: '0.1.0',
                    wallet: '0.1.0',
                    walletPayments: '0.1.0',
                  },
                },
                futureValues: [],
              },
              isDevNet: true,
            }),
            created_event_blob: '',
            created_at: '2023-10-06T08:12:16.481271Z',
          },
          domain_id:
            'global-domain::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
        },
      })
    );
  }),
  rest.get<null, { partyId: string }, LookupEntryByPartyResponse | ErrorResponse>(
    `${scanUrl}/v0/ans-entries/by-party/:partyId`,
    (req, res, ctx) => {
      if (
        req.params['partyId'] ===
        'charlie__wallet__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7'
      ) {
        return res(
          ctx.json<LookupEntryByPartyResponse>({
            entry: {
              contract_id:
                '00a0e1386b02ea75f0ddcfc7c4fbfb8eba09cd3c3748b160de1f17450bb99faaa7ca0212209680fb7e9526ddccf5931db169bf8ba16e4e60d7e23a74c28e9492e8b62d1194',
              user: 'google-oauth2_007c106265882859845879513::122033667ff9ec083bf5a6b512655bd7986dfc4d6644978c944129a0f46489bc41d4',
              name: `charlie.unverified.${amuletNameServiceAcronym.toLowerCase()}`,
              url: '',
              description: '',
              expires_at: new Date('2024-01-04T07:37:05.004139Z'),
            },
          })
        );
      } else {
        return res(
          ctx.status(404),
          ctx.json({
            error:
              'No ans entry found for party: alice::122015ba7aa9054dbad217110e8fbf5dd550a59fb56df5986913f7b9a8e63bad8570',
          })
        );
      }
    }
  ),
  validatorLicensesHandler(scanUrl),
];
