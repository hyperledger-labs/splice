import { RestHandler, rest } from 'msw';
import {
  ErrorResponse,
  GetCoinRulesResponse,
  GetRewardsCollectedResponse,
  GetRoundOfLatestDataResponse,
  GetSvcPartyIdResponse,
  GetTotalCoinBalanceResponse,
  ListActivityResponse,
  LookupEntryByPartyResponse,
} from 'scan-openapi';

import { CoinRules } from '@daml.js/canton-coin/lib/CC/CoinRules/module';
import { CnsEntry } from '@daml.js/cns/lib/CN/Cns/module';
import damlTypes from '@daml/types';

export const buildScanMock = (scanUrl: string): RestHandler[] => [
  rest.get(`${scanUrl}/v0/svc-party-id`, (_, res, ctx) => {
    return res(
      ctx.json<GetSvcPartyIdResponse>({
        svc_party_id: 'svc::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
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
            coin_price: '1.0000000000',
            round: 1,
            transfer: {
              provider:
                'alice__validator__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7',
              sender: {
                party:
                  'charlie__wallet__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7',
                input_coin_amount: '5.0000000000',
                input_app_reward_amount: '0.0000000000',
                input_validator_reward_amount: '0.0000000000',
                sender_change_fee: '0.0300000000',
                sender_change_amount: '3.8950000000',
                sender_fee: '0.0000000000',
                holding_fees: '0.0000000000',
              },
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
  rest.get(`${scanUrl}/v0/total-coin-balance`, (_, res, ctx) => {
    return res(ctx.json<GetTotalCoinBalanceResponse>({ total_balance: '66605.2180742781' }));
  }),
  rest.post(`${scanUrl}/v0/coin-rules`, (_, res, ctx) => {
    return res(
      ctx.json<GetCoinRulesResponse>({
        coin_rules_update: {
          contract: {
            template_id:
              'f4693252b3cab434649f66f5fd309ae98ca01512b10b482086aa8ff529ca83e3:CC.Coin:CoinRules',
            contract_id:
              '00ed7531fa0fb6a06f0d0f1ea8a31867704da8a6c341e7262894c5d0e15312aca6ca0212200412a9e6c1b9bff1449205e02c88596bad60b8eb8d14bee48f26509f6531d4db',
            payload: CoinRules.encode({
              svc: 'svc::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
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
                      coinToIssuePerYear: '40000000000.0',
                      unfeaturedAppRewardCap: '0.6',
                      appRewardPercentage: '0.15',
                      featuredAppRewardCap: '100.0',
                      validatorRewardCap: '0.2',
                    },
                    futureValues: [
                      {
                        _1: {
                          microseconds: '15768000000000',
                        },
                        _2: {
                          validatorRewardPercentage: '0.12',
                          coinToIssuePerYear: '20000000000.0',
                          unfeaturedAppRewardCap: '0.6',
                          appRewardPercentage: '0.4',
                          featuredAppRewardCap: '100.0',
                          validatorRewardCap: '0.2',
                        },
                      },
                      {
                        _1: {
                          microseconds: '47304000000000',
                        },
                        _2: {
                          validatorRewardPercentage: '0.18',
                          coinToIssuePerYear: '10000000000.0',
                          unfeaturedAppRewardCap: '0.6',
                          appRewardPercentage: '0.62',
                          featuredAppRewardCap: '100.0',
                          validatorRewardCap: '0.2',
                        },
                      },
                      {
                        _1: {
                          microseconds: '157680000000000',
                        },
                        _2: {
                          validatorRewardPercentage: '0.21',
                          coinToIssuePerYear: '5000000000.0',
                          unfeaturedAppRewardCap: '0.6',
                          appRewardPercentage: '0.69',
                          featuredAppRewardCap: '100.0',
                          validatorRewardCap: '0.2',
                        },
                      },
                      {
                        _1: {
                          microseconds: '315360000000000',
                        },
                        _2: {
                          validatorRewardPercentage: '0.2',
                          coinToIssuePerYear: '2500000000.0',
                          unfeaturedAppRewardCap: '0.6',
                          appRewardPercentage: '0.75',
                          featuredAppRewardCap: '100.0',
                          validatorRewardCap: '0.2',
                        },
                      },
                    ],
                  },

                  globalDomain: {
                    requiredDomains: {
                      map: damlTypes
                        .emptyMap<string, object>()
                        .set(
                          'global-domain::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
                          {}
                        ),
                    },
                    activeDomain:
                      'global-domain::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
                    fees: {
                      baseRateTrafficLimits: {
                        burstAmount: '2000000',
                        burstWindow: {
                          microseconds: '600000000',
                        },
                      },
                      extraTrafficPrice: '1.0',
                      readVsWriteScalingFactor: '200',
                      minTopupAmount: '1000',
                    },
                  },
                  tickDuration: {
                    microseconds: '150000000',
                  },
                  packageConfig: {
                    cantonCoin: '0.1.0',
                    cantonNameService: '0.1.0',
                    directoryService: '0.1.0',
                    svcGovernance: '0.1.0',
                    validatorLifecycle: '0.1.0',
                    wallet: '0.1.0',
                    walletPayments: '0.1.0',
                  },
                },
                futureValues: [],
              },
              isDevNet: true,
              upgrade: null,
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
    `${scanUrl}/v0/cns-entries/by-party/:partyId`,
    (req, res, ctx) => {
      if (
        req.params['partyId'] ===
        'charlie__wallet__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7'
      ) {
        return res(
          ctx.json<LookupEntryByPartyResponse>({
            entry: {
              template_id:
                'ab605eca7c09dd64625e95e4a1d632058276f05fbdfe2f67009e66f35d3b3106:CN.Cns:CnsEntry',
              contract_id:
                '00a0e1386b02ea75f0ddcfc7c4fbfb8eba09cd3c3748b160de1f17450bb99faaa7ca0212209680fb7e9526ddccf5931db169bf8ba16e4e60d7e23a74c28e9492e8b62d1194',
              payload: CnsEntry.encode({
                name: 'charlie.unverified.cns',
                svc: 'svc::1220164c57d911c28c81575446053818b21dc83b24cf10b22c622663e0e282d85a57',
                url: '',
                description: '',
                expiresAt: '2024-01-04T07:37:05.004139Z',
                user: 'google-oauth2_007c106265882859845879513::122033667ff9ec083bf5a6b512655bd7986dfc4d6644978c944129a0f46489bc41d4',
              }),
              created_event_blob: '',
              created_at: '2023-10-06T07:37:05.004139Z',
            },
          })
        );
      } else {
        return res(
          ctx.status(404),
          ctx.json({
            error:
              'No cns entry found for party: alice::122015ba7aa9054dbad217110e8fbf5dd550a59fb56df5986913f7b9a8e63bad8570',
          })
        );
      }
    }
  ),
];
