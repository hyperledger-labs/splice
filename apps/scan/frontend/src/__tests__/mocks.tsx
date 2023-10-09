import { CoinRules } from 'common-frontend/daml.js/canton-coin-0.1.0/lib/CC/Coin/module';
import { DirectoryEntry } from 'common-frontend/daml.js/directory-service-0.1.0/lib/CN/Directory/module';
import { ErrorResponse, LookupEntryByPartyResponse } from 'directory-openapi';
import { RestHandler, rest } from 'msw';
import {
  GetCoinRulesResponse,
  GetRewardsCollectedResponse,
  GetRoundOfLatestDataResponse,
  GetSvcPartyIdResponse,
  GetTotalCoinBalanceResponse,
  ListActivityResponse,
} from 'scan-openapi';

import damlTypes from '@daml/types';

export const buildScanMock = (scanUrl: string): RestHandler[] => [
  rest.get(`${scanUrl}/svc-party-id`, (_, res, ctx) => {
    return res(
      ctx.json<GetSvcPartyIdResponse>({
        svc_party_id: 'svc::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
      })
    );
  }),
  rest.post(`${scanUrl}/activities`, (_, res, ctx) => {
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
  rest.get(`${scanUrl}/round-of-latest-data`, (_, res, ctx) => {
    return res(ctx.json<GetRoundOfLatestDataResponse>({ round: 1, effectiveAt: new Date() }));
  }),
  rest.get(`${scanUrl}/rewards-collected`, (_, res, ctx) => {
    return res(ctx.json<GetRewardsCollectedResponse>({ amount: '0.0' }));
  }),
  rest.get(`${scanUrl}/total-coin-balance`, (_, res, ctx) => {
    return res(ctx.json<GetTotalCoinBalanceResponse>({ total_balance: '66605.2180742781' }));
  }),
  rest.post(`${scanUrl}/coin-rules`, (_, res, ctx) => {
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
                        rate: '3333.0',
                        burstWindow: {
                          microseconds: '600000000',
                        },
                      },
                      extraTrafficPrice: '1.0',
                      readScalingFactor: '0.02',
                      minTopupAmount: '1000',
                    },
                  },
                  tickDuration: {
                    microseconds: '150000000',
                  },
                },
                futureValues: [],
              },
              isDevNet: true,
              lock: false,
            }),
            metadata: {
              createdAt: '2023-10-06T08:12:16.481271Z',
              contractKeyHash: '',
              driverMetadata: 'CiYKJAgBEiBqc5xj84d-yWt2ckLRXUenjSJJfLRVnaBMBsn_3IApMg==',
            },
          },
          domain_id:
            'global-domain::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
        },
      })
    );
  }),
];

export const buildDirectoryMock = (directoryUrl: string): RestHandler[] => [
  rest.get<null, { partyId: string }, LookupEntryByPartyResponse | ErrorResponse>(
    `${directoryUrl}/entries/by-party/:partyId`,
    (req, res, ctx) => {
      if (
        req.params['partyId'] ===
        'charlie__wallet__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7'
      ) {
        return res(
          ctx.json<LookupEntryByPartyResponse>({
            entry: {
              template_id:
                'ab605eca7c09dd64625e95e4a1d632058276f05fbdfe2f67009e66f35d3b3106:CN.Directory:DirectoryEntry',
              contract_id:
                '00a0e1386b02ea75f0ddcfc7c4fbfb8eba09cd3c3748b160de1f17450bb99faaa7ca0212209680fb7e9526ddccf5931db169bf8ba16e4e60d7e23a74c28e9492e8b62d1194',
              payload: DirectoryEntry.encode({
                name: 'charlie.unverified.cns',
                provider:
                  'svc::1220164c57d911c28c81575446053818b21dc83b24cf10b22c622663e0e282d85a57',
                url: '',
                description: '',
                expiresAt: '2024-01-04T07:37:05.004139Z',
                user: 'google-oauth2_007c106265882859845879513::122033667ff9ec083bf5a6b512655bd7986dfc4d6644978c944129a0f46489bc41d4',
              }),
              metadata: {
                createdAt: '2023-10-06T07:37:05.004139Z',
                contractKeyHash: '',
                driverMetadata: 'CiYKJAgBEiCXgBoBsVAjxBQQBHjwClCXH2Q36rFqPdR9wr9OKphDqQ==',
              },
            },
          })
        );
      } else {
        return res(
          ctx.status(404),
          ctx.json({
            error:
              'No directory entry found for party: alice::122015ba7aa9054dbad217110e8fbf5dd550a59fb56df5986913f7b9a8e63bad8570',
          })
        );
      }
    }
  ),
];
