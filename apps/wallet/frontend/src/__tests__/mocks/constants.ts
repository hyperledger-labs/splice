// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { TransferPreapproval } from '@daml.js/splice-amulet/lib/Splice/AmuletRules/module';

import { config } from '../setup/config';

export const userLogin = 'alice_wallet_user';

export const alicePartyId =
  'alice__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2';

export const bobPartyId =
  'bob__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e3';

export const nameServiceAcronym = config.spliceInstanceNames.nameServiceNameAcronym.toLowerCase();

export const aliceEntry = {
  contract_id:
    '00c45cf4830e29b524d0cf0f8febff54fcf1b9e889d11be200ae77ff15efb59317ca1012206c6f22a4b9ed6bae39fc16f388bc62b33f87a1ab490158b99954c4a156bae8bd',
  user: alicePartyId,
  name: `alice.unverified.${nameServiceAcronym}`,
  url: 'https://alice-url.ans.com',
  description: '',
  expires_at: new Date('2024-11-03T13:45:14.32091Z'),
};

export const nameServiceEntries = [
  {
    contract_id:
      '00c45cf4830e29b524d0cf0f8febff54fcf1b9e889d11be200ae77ff15efb59317ca1012206c6f22a4b9ed6bae39fc16f388bc62b33f87a1ab490158b99954c4a156bae8bd',
    user: alicePartyId,
    name: `alice.unverified.${nameServiceAcronym}`,
    url: 'https://alice-url.ans.com',
    description: '',
    expires_at: new Date('2024-11-03T13:45:14.32091Z'),
  },
  {
    contract_id:
      '0009cea12d7a024eb95dbe628f089545470390a38d1264c187dea0f7bf5d35b9d1ca10122067884f209c8b58dd0814a33096f0350b3eff39d9c1c6ca8daae7fac03eae4c6c',
    user: 'bob__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2',
    name: `bob.unverified.${nameServiceAcronym}`,
    url: 'https://bob-url.ans.com',
    description: '',
    expires_at: new Date('2024-11-03T13:45:18.934865Z'),
  },
  {
    contract_id:
      '0093e2c079f80a5992c7b91659993045c0ee7449c2fb0b41729a735a5c93434984ca1012207269b6645c44a9ef34b3daecb94f8c4799af2ad0433acd3ca513c537663abc55',
    user: 'charlie__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2',
    name: `charlie.unverified.${nameServiceAcronym}`,
    url: 'https://charlie-url.ans.com',
    description: '',
    expires_at: new Date('2024-11-03T13:45:23.029827Z'),
  },
  {
    contract_id: null,
    user: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
    name: `dso.${nameServiceAcronym}`,
    url: '',
    description: '',
    expires_at: null,
  },
  {
    contract_id: null,
    user: 'digital-asset-2::12207956fc4908973864aea4aaf2557a2c560c14eb4e23f738c34aa31ee8853475cb',
    name: `digital-asset-2.sv.${nameServiceAcronym}`,
    url: '',
    description: '',
    expires_at: null,
  },
];

export const bobTransferPreapproval = {
  contract: {
    template_id:
      'a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.AmuletRules:TransferPreapproval',
    contract_id:
      '00df78090974813f7c46ae339079ad5728055513f59d8586eddf431413b760d89cca101220054d0121ad7a38f64787cc262d7f39bcef6557919a9f7203981576c95c578df0',
    payload: TransferPreapproval.encode({
      dso: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
      receiver: 'bob::preapproval',
      provider: 'bob::preapproval',
      validFrom: '2024-08-05T16:50:50.974657Z',
      lastRenewedAt: '2024-08-05T16:50:50.974657Z',
      expiresAt: '2024-09-05T16:50:50.974657Z',
    }),
    created_event_blob:
      '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',
    created_at: '2024-08-05T13:44:35.878681Z',
  },
  domain_id: 'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
};

export const aliceTransferPreapproval = {
  contract: {
    template_id:
      'ea77bb255205999e75fa88faa06d228a62d7cf82dff249081079d3e8ae4f01fc:Splice.AmuletRules:TransferPreapproval',
    contract_id:
      '008ccb034f80137091417093367b06aea7ef1db3b81641fc6b235a5b06c0c8c370ca101220b3e427d522de646cd9f2a81d35a70fcc03c3602f75f07d3356987c25b2077b28',
    payload: {
      dso: 'DSO::122010c424982ba7befadd6a14131ac8b5021958e924bd1d3cbca9b44f8727e329ee',
      expiresAt: '2025-02-03T05:02:56.720670Z',
      receiver: alicePartyId,
      validFrom: '2024-11-05T05:02:56.732754Z',
      provider:
        'alice-validator-1::12208214ed2f54232298f1e81f83f084af7e4dd84f75e916bcf0cc8d99fe2b48ac8f',
      lastRenewedAt: '2024-11-05T05:02:56.732754Z',
    },
    created_event_blob:
      'CgMyLjESsAYKRQCMywNPgBNwkUFwkzZ7Bq6n7x2zuBZB/GsjWlsGwMjDcMoQEiCz5CfVIt5kbNnyqB01pw/MA8NgL3XwfTNWmHwlsgd7KBINc3BsaWNlLWFtdWxldBpsCkBlYTc3YmIyNTUyMDU5OTllNzVmYTg4ZmFhMDZkMjI4YTYyZDdjZjgyZGZmMjQ5MDgxMDc5ZDNlOGFlNGYwMWZjEgZTcGxpY2USC0FtdWxldFJ1bGVzGhNUcmFuc2ZlclByZWFwcHJvdmFsIrUCarICCk0KSzpJRFNPOjoxMjIwMTBjNDI0OTgyYmE3YmVmYWRkNmExNDEzMWFjOGI1MDIxOTU4ZTkyNGJkMWQzY2JjYTliNDRmODcyN2UzMjllZQpdCls6WWFsaWNlX193YWxsZXRfX3VzZXI6OjEyMjA4MjE0ZWQyZjU0MjMyMjk4ZjFlODFmODNmMDg0YWY3ZTRkZDg0Zjc1ZTkxNmJjZjBjYzhkOTlmZTJiNDhhYzhmClsKWTpXYWxpY2UtdmFsaWRhdG9yLTE6OjEyMjA4MjE0ZWQyZjU0MjMyMjk4ZjFlODFmODNmMDg0YWY3ZTRkZDg0Zjc1ZTkxNmJjZjBjYzhkOTlmZTJiNDhhYzhmCgsKCSlS7oJQIyYGAAoLCgkpUu6CUCMmBgAKCwoJKR5/Os41LQYAKklEU086OjEyMjAxMGM0MjQ5ODJiYTdiZWZhZGQ2YTE0MTMxYWM4YjUwMjE5NThlOTI0YmQxZDNjYmNhOWI0NGY4NzI3ZTMyOWVlKldhbGljZS12YWxpZGF0b3ItMTo6MTIyMDgyMTRlZDJmNTQyMzIyOThmMWU4MWY4M2YwODRhZjdlNGRkODRmNzVlOTE2YmNmMGNjOGQ5OWZlMmI0OGFjOGYqWWFsaWNlX193YWxsZXRfX3VzZXI6OjEyMjA4MjE0ZWQyZjU0MjMyMjk4ZjFlODFmODNmMDg0YWY3ZTRkZDg0Zjc1ZTkxNmJjZjBjYzhkOTlmZTJiNDhhYzhmOVLuglAjJgYAQioKJgokCAESICA0JODCs5vjO9IpNdmdNL4APOZg8u1gtzLnY+u8DtyrEB4=',
    created_at: '2024-11-05T05:02:56.732754Z',
  },
  domain_id: 'global-domain::122010c424982ba7befadd6a14131ac8b5021958e924bd1d3cbca9b44f8727e329ee',
};

export const amuletRules = {
  amulet_rules: {
    contract: {
      template_id:
        'a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.AmuletRules:AmuletRules',
      contract_id:
        '00df78090974813f7c46ae339079ad5728055513f59d8586eddf431413b760d89cca101220054d0121ad7a38f64787cc262d7f39bcef6557919a9f7203981576c95c578df0',
      payload: {
        dso: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
        configSchedule: {
          initialValue: {
            packageConfig: {
              amuletNameService: '0.1.4',
              walletPayments: '0.1.4',
              dsoGovernance: '0.1.7',
              validatorLifecycle: '0.1.1',
              amulet: '0.1.4',
              wallet: '0.1.4',
            },
            tickDuration: {
              microseconds: '600000000',
            },
            decentralizedSynchronizer: {
              requiredSynchronizers: {
                map: [
                  [
                    'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
                    {},
                  ],
                ],
              },
              activeSynchronizer:
                'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
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
            transferConfig: {
              holdingFee: {
                rate: '0.0000190259',
              },
              extraFeaturedAppRewardAmount: '1.0',
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
                validatorRewardPercentage: '0.05',
                unfeaturedAppRewardCap: '0.6',
                appRewardPercentage: '0.15',
                featuredAppRewardCap: '100.0',
                amuletToIssuePerYear: '40000000000.0',
                validatorRewardCap: '0.2',
                optValidatorFaucetCap: '2.85',
                optDevelopmentFundPercentage: '0.05',
              },
              futureValues: [
                {
                  _1: {
                    microseconds: '15768000000000',
                  },
                  _2: {
                    validatorRewardPercentage: '0.12',
                    unfeaturedAppRewardCap: '0.6',
                    appRewardPercentage: '0.4',
                    featuredAppRewardCap: '100.0',
                    amuletToIssuePerYear: '20000000000.0',
                    validatorRewardCap: '0.2',
                    optValidatorFaucetCap: '2.85',
                    optDevelopmentFundPercentage: '0.05',
                  },
                },
                {
                  _1: {
                    microseconds: '47304000000000',
                  },
                  _2: {
                    validatorRewardPercentage: '0.18',
                    unfeaturedAppRewardCap: '0.6',
                    appRewardPercentage: '0.62',
                    featuredAppRewardCap: '100.0',
                    amuletToIssuePerYear: '10000000000.0',
                    validatorRewardCap: '0.2',
                    optValidatorFaucetCap: '2.85',
                    optDevelopmentFundPercentage: '0.05',
                  },
                },
                {
                  _1: {
                    microseconds: '157680000000000',
                  },
                  _2: {
                    validatorRewardPercentage: '0.21',
                    unfeaturedAppRewardCap: '0.6',
                    appRewardPercentage: '0.69',
                    featuredAppRewardCap: '100.0',
                    amuletToIssuePerYear: '5000000000.0',
                    validatorRewardCap: '0.2',
                    optValidatorFaucetCap: '2.85',
                    optDevelopmentFundPercentage: '0.05',
                  },
                },
                {
                  _1: {
                    microseconds: '315360000000000',
                  },
                  _2: {
                    validatorRewardPercentage: '0.2',
                    unfeaturedAppRewardCap: '0.6',
                    appRewardPercentage: '0.75',
                    featuredAppRewardCap: '100.0',
                    amuletToIssuePerYear: '2500000000.0',
                    validatorRewardCap: '0.2',
                    optValidatorFaucetCap: '2.85',
                    optDevelopmentFundPercentage: '0.05',
                  },
                },
              ],
            },
            transferPreapprovalFee: null,
            featuredAppActivityMarkerAmount: null,
            optDevelopmentFundManager: null,
          },
          futureValues: [],
        },
        isDevNet: true,
      },
      created_event_blob:
        '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',
      created_at: '2024-08-05T13:44:35.878681Z',
    },
    domain_id:
      'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
  },
};

export const miningRounds = {
  open_mining_rounds: [
    {
      contract: {
        template_id:
          'a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.Round:OpenMiningRound',
        contract_id:
          '008f1ad943696bb1995fdf8f245986efbba6f81377e0363c8294af5c1a154148d1ca101220117c052f6cc7f09a9d7b1d475c59c907f88d3f569c16671e3a978a704963a0a7',
        payload: {
          dso: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
          tickDuration: {
            microseconds: '600000000',
          },
          issuingFor: {
            microseconds: '10200000000',
          },
          amuletPrice: '0.005',
          issuanceConfig: {
            validatorRewardPercentage: '0.05',
            unfeaturedAppRewardCap: '0.6',
            appRewardPercentage: '0.15',
            featuredAppRewardCap: '100.0',
            amuletToIssuePerYear: '40000000000.0',
            validatorRewardCap: '0.2',
            optValidatorFaucetCap: '2.85',
            optDevelopmentFundPercentage: '0.05',
          },
          opensAt: '2024-08-05T16:30:50.974657Z',
          transferConfigUsd: {
            holdingFee: {
              rate: '0.0000190259',
            },
            extraFeaturedAppRewardAmount: '1.0',
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
          targetClosesAt: '2024-08-05T16:50:50.974657Z',
          round: {
            number: '17',
          },
        },
        created_event_blob:
          'CgMyLjESnAcKRQCPGtlDaWuxmV/fjyRZhu+7pvgTd+A2PIKUr1waFUFI0coQEiARfAUvbMfwmp17HUdcWckH+I0/VpwWZx46l4pwSWOgpxINc3BsaWNlLWFtdWxldBpiCkBhMzZlZjg4ODhmYjQ0Y2FhZTEzZDk2MzQxY2UxZmFiZDg0ZmM5ZTJlN2IyMDliYmMzY2FhYmI0OGI2YmUxNjY4EgZTcGxpY2USBVJvdW5kGg9PcGVuTWluaW5nUm91bmQi3wRq3AQKTQpLOklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4CgoKCGoGCgQKAhgiChAKDjIMMC4wMDUwMDAwMDAwCgsKCSnBYT8z8h4GAAoLCgkpwe3FevIeBgAKDgoMagoKCAoGGICYvf9LCpsCCpgCapUCChYKFGoSChAKDjIMMC4wMzAwMDAwMDAwChYKFGoSChAKDjIMMC4wMDAwMTkwMjU5CqQBCqEBap4BChAKDjIMMC4wMTAwMDAwMDAwCokBCoYBWoMBCihqJgoSChAyDjEwMC4wMDAwMDAwMDAwChAKDjIMMC4wMDEwMDAwMDAwCilqJwoTChEyDzEwMDAuMDAwMDAwMDAwMAoQCg4yDDAuMDAwMTAwMDAwMAosaioKFgoUMhIxMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjAwMDAxMDAwMDAKFgoUahIKEAoOMgwwLjAwNTAwMDAwMDAKEAoOMgwxLjAwMDAwMDAwMDAKBQoDGMgBCgUKAxjIAQoECgIYZAqUAQqRAWqOAQoaChgyFjQwMDAwMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjA1MDAwMDAwMDAKEAoOMgwwLjE1MDAwMDAwMDAKEAoOMgwwLjIwMDAwMDAwMDAKEgoQMg4xMDAuMDAwMDAwMDAwMAoQCg4yDDAuNjAwMDAwMDAwMAoUChJSEAoOMgwyLjg1MDAwMDAwMDAKDgoMagoKCAoGGICYmrwEKklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4OcEbfA/yHgYAQioKJgokCAESIJEpwXX6ktkV0P7Qxg6BvRO84BKv13IOJI5i+k/hezTDEB4=',
        created_at: '2024-08-05T16:20:50.974657Z',
      },
      domain_id:
        'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
    },
    {
      contract: {
        template_id:
          'a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.Round:OpenMiningRound',
        contract_id:
          '00add7ff3da79056299f904a8d3d857f1e4917d89ce415011ac90d7cf765a1ad2eca1012206905e70ceb53bf49b8f6f58059138a596a88803502cf97eb5eee3869774df4cb',
        payload: {
          dso: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
          tickDuration: {
            microseconds: '600000000',
          },
          issuingFor: {
            microseconds: '10800000000',
          },
          amuletPrice: '0.005',
          issuanceConfig: {
            validatorRewardPercentage: '0.05',
            unfeaturedAppRewardCap: '0.6',
            appRewardPercentage: '0.15',
            featuredAppRewardCap: '100.0',
            amuletToIssuePerYear: '40000000000.0',
            validatorRewardCap: '0.2',
            optValidatorFaucetCap: '2.85',
            optDevelopmentFundPercentage: '0.05',
          },
          opensAt: '2024-08-05T16:41:21.419411Z',
          transferConfigUsd: {
            holdingFee: {
              rate: '0.0000190259',
            },
            extraFeaturedAppRewardAmount: '1.0',
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
          targetClosesAt: '2024-08-05T17:01:21.419411Z',
          round: {
            number: '18',
          },
        },
        created_event_blob:
          'CgMyLjESnAcKRQCt1/89p5BWKZ+QSo09hX8eSRfYnOQVARrJDXz3ZaGtLsoQEiBpBecM61O/Sbj29YBZE4pZaoiANQLPl+te7jhpd030yxINc3BsaWNlLWFtdWxldBpiCkBhMzZlZjg4ODhmYjQ0Y2FhZTEzZDk2MzQxY2UxZmFiZDg0ZmM5ZTJlN2IyMDliYmMzY2FhYmI0OGI2YmUxNjY4EgZTcGxpY2USBVJvdW5kGg9PcGVuTWluaW5nUm91bmQi3wRq3AQKTQpLOklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4CgoKCGoGCgQKAhgkChAKDjIMMC4wMDUwMDAwMDAwCgsKCSmTNNNY8h4GAAoLCgkpk8BZoPIeBgAKDgoMagoKCAoGGICw17tQCpsCCpgCapUCChYKFGoSChAKDjIMMC4wMzAwMDAwMDAwChYKFGoSChAKDjIMMC4wMDAwMTkwMjU5CqQBCqEBap4BChAKDjIMMC4wMTAwMDAwMDAwCokBCoYBWoMBCihqJgoSChAyDjEwMC4wMDAwMDAwMDAwChAKDjIMMC4wMDEwMDAwMDAwCilqJwoTChEyDzEwMDAuMDAwMDAwMDAwMAoQCg4yDDAuMDAwMTAwMDAwMAosaioKFgoUMhIxMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjAwMDAxMDAwMDAKFgoUahIKEAoOMgwwLjAwNTAwMDAwMDAKEAoOMgwxLjAwMDAwMDAwMDAKBQoDGMgBCgUKAxjIAQoECgIYZAqUAQqRAWqOAQoaChgyFjQwMDAwMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjA1MDAwMDAwMDAKEAoOMgwwLjE1MDAwMDAwMDAKEAoOMgwwLjIwMDAwMDAwMDAKEgoQMg4xMDAuMDAwMDAwMDAwMAoQCg4yDDAuNjAwMDAwMDAwMAoUChJSEAoOMgwyLjg1MDAwMDAwMDAKDgoMagoKCAoGGICYmrwEKklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4OZPuDzXyHgYAQioKJgokCAESIGewl0L/2U0pzvutw4MmtSwemC/wJNVYCtYwvjcLxYxWEB4=',
        created_at: '2024-08-05T16:31:21.419411Z',
      },
      domain_id:
        'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
    },
    {
      contract: {
        template_id:
          'a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.Round:OpenMiningRound',
        contract_id:
          '0023ad43f9683a6da5fdbc62dcc7e3f68364e32fd7b0c868f861172918f7440999ca10122075154b3c5570d8c7dd69fd1f92999aaad61eea23c1a17fc08e86eb360eb7d103',
        payload: {
          dso: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
          tickDuration: {
            microseconds: '600000000',
          },
          issuingFor: {
            microseconds: '11400000000',
          },
          amuletPrice: '0.005',
          issuanceConfig: {
            validatorRewardPercentage: '0.05',
            unfeaturedAppRewardCap: '0.6',
            appRewardPercentage: '0.15',
            featuredAppRewardCap: '100.0',
            amuletToIssuePerYear: '40000000000.0',
            validatorRewardCap: '0.2',
            optValidatorFaucetCap: '2.85',
            optDevelopmentFundPercentage: '0.05',
          },
          opensAt: '2024-08-05T16:51:47.336118Z',
          transferConfigUsd: {
            holdingFee: {
              rate: '0.0000190259',
            },
            extraFeaturedAppRewardAmount: '1.0',
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
          targetClosesAt: '2024-08-05T17:11:47.336118Z',
          round: {
            number: '19',
          },
        },
        created_event_blob:
          'CgMyLjESnAcKRQAjrUP5aDptpf28YtzH4/aDZOMv17DIaPhhFykY90QJmcoQEiB1FUs8VXDYx91p/R+SmZqq1h7qI8Ghf8COhus2DrfRAxINc3BsaWNlLWFtdWxldBpiCkBhMzZlZjg4ODhmYjQ0Y2FhZTEzZDk2MzQxY2UxZmFiZDg0ZmM5ZTJlN2IyMDliYmMzY2FhYmI0OGI2YmUxNjY4EgZTcGxpY2USBVJvdW5kGg9PcGVuTWluaW5nUm91bmQi3wRq3AQKTQpLOklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4CgoKCGoGCgQKAhgmChAKDjIMMC4wMDUwMDAwMDAwCgsKCSm27yF+8h4GAAoLCgkptnuoxfIeBgAKDgoMagoKCAoGGIDI8fdUCpsCCpgCapUCChYKFGoSChAKDjIMMC4wMzAwMDAwMDAwChYKFGoSChAKDjIMMC4wMDAwMTkwMjU5CqQBCqEBap4BChAKDjIMMC4wMTAwMDAwMDAwCokBCoYBWoMBCihqJgoSChAyDjEwMC4wMDAwMDAwMDAwChAKDjIMMC4wMDEwMDAwMDAwCilqJwoTChEyDzEwMDAuMDAwMDAwMDAwMAoQCg4yDDAuMDAwMTAwMDAwMAosaioKFgoUMhIxMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjAwMDAxMDAwMDAKFgoUahIKEAoOMgwwLjAwNTAwMDAwMDAKEAoOMgwxLjAwMDAwMDAwMDAKBQoDGMgBCgUKAxjIAQoECgIYZAqUAQqRAWqOAQoaChgyFjQwMDAwMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjA1MDAwMDAwMDAKEAoOMgwwLjE1MDAwMDAwMDAKEAoOMgwwLjIwMDAwMDAwMDAKEgoQMg4xMDAuMDAwMDAwMDAwMAoQCg4yDDAuNjAwMDAwMDAwMAoUChJSEAoOMgwyLjg1MDAwMDAwMDAKDgoMagoKCAoGGICYmrwEKklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4ObapXlryHgYAQioKJgokCAESINVgEFqydCs507MxN6l8VmdlLjHG39c+p3oFjjQ5oakbEB4=',
        created_at: '2024-08-05T16:41:47.336118Z',
      },
      domain_id:
        'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
    },
  ],
  issuing_mining_rounds: [
    {
      contract: {
        template_id:
          'a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.Round:IssuingMiningRound',
        contract_id:
          '00cee69561e8f1486dffde8a7067113b9cee48bd944c0fb3eda36569039a147a66ca10122048ba03a140e6f806373055060a7859aa542634e5954ead325dac2a1cd0ea36e0',
        payload: {
          dso: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
          optIssuancePerValidatorFaucetCoupon: '570.0',
          issuancePerFeaturedAppRewardCoupon: '100.0',
          opensAt: '2024-08-05T16:31:12.468130Z',
          issuancePerSvRewardCoupon: '60.8828006088',
          targetClosesAt: '2024-08-05T16:51:12.468130Z',
          issuancePerUnfeaturedAppRewardCoupon: '0.6',
          round: {
            number: '14',
          },
          issuancePerValidatorRewardCoupon: '0.2',
        },
        created_event_blob:
          'CgMyLjESmwQKRQDO5pVh6PFIbf/einBnETuc7ki9lEwPs+2jZWkDmhR6ZsoQEiBIugOhQOb4BjcwVQYKeFmqVCY05ZVOrTJdrCoc0Oo24BINc3BsaWNlLWFtdWxldBplCkBhMzZlZjg4ODhmYjQ0Y2FhZTEzZDk2MzQxY2UxZmFiZDg0ZmM5ZTJlN2IyMDliYmMzY2FhYmI0OGI2YmUxNjY4EgZTcGxpY2USBVJvdW5kGhJJc3N1aW5nTWluaW5nUm91bmQi2wFq2AEKTQpLOklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4CgoKCGoGCgQKAhgcChAKDjIMMC4yMDAwMDAwMDAwChIKEDIOMTAwLjAwMDAwMDAwMDAKEAoOMgwwLjYwMDAwMDAwMDAKEQoPMg02MC44ODI4MDA2MDg4CgsKCSmiWIc08h4GAAoLCgkpouQNfPIeBgAKFgoUUhIKEDIONTcwLjAwMDAwMDAwMDAqSURTTzo6MTIyMGZiODlkNjI3NzRiZDViM2ZkOGExMWMxYjIyYzhjNTQ1M2U4Mjg2YzNjZjdhZGQ1MTVjOThkN2JjYTE5MmVmMTg5ohLEEPIeBgBCKgomCiQIARIggmmQUbzwDQdYI6Q34vomaygXiTHMtMn9pxB5qlKfoJQQHg==',
        created_at: '2024-08-05T16:21:12.468130Z',
      },
      domain_id:
        'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
    },
    {
      contract: {
        template_id:
          'a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.Round:IssuingMiningRound',
        contract_id:
          '001c13e2927e558486a700708be04e63d9186632787db0cad1274876919a86656cca101220a001ec3d03835053af66747b2e641217965756172c5ae6bac583a8b777f8f2af',
        payload: {
          dso: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
          optIssuancePerValidatorFaucetCoupon: '570.0',
          issuancePerFeaturedAppRewardCoupon: '100.0',
          opensAt: '2024-08-05T16:41:52.754993Z',
          issuancePerSvRewardCoupon: '60.8828006088',
          targetClosesAt: '2024-08-05T17:01:52.754993Z',
          issuancePerUnfeaturedAppRewardCoupon: '0.6',
          round: {
            number: '15',
          },
          issuancePerValidatorRewardCoupon: '0.2',
        },
        created_event_blob:
          'CgMyLjESmwQKRQAcE+KSflWEhqcAcIvgTmPZGGYyeH2wytEnSHaRmoZlbMoQEiCgAew9A4NQU69mdHsuZBIXlldWFyxa5rrFg6i3d/jyrxINc3BsaWNlLWFtdWxldBplCkBhMzZlZjg4ODhmYjQ0Y2FhZTEzZDk2MzQxY2UxZmFiZDg0ZmM5ZTJlN2IyMDliYmMzY2FhYmI0OGI2YmUxNjY4EgZTcGxpY2USBVJvdW5kGhJJc3N1aW5nTWluaW5nUm91bmQi2wFq2AEKTQpLOklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4CgoKCGoGCgQKAhgeChAKDjIMMC4yMDAwMDAwMDAwChIKEDIOMTAwLjAwMDAwMDAwMDAKEAoOMgwwLjYwMDAwMDAwMDAKEQoPMg02MC44ODI4MDA2MDg4CgsKCSkxWbFa8h4GAAoLCgkpMeU3ovIeBgAKFgoUUhIKEDIONTcwLjAwMDAwMDAwMDAqSURTTzo6MTIyMGZiODlkNjI3NzRiZDViM2ZkOGExMWMxYjIyYzhjNTQ1M2U4Mjg2YzNjZjdhZGQ1MTVjOThkN2JjYTE5MmVmMTg5MRPuNvIeBgBCKgomCiQIARIg/qNxL3v15Ic6tp0Ic75gGhxad5EKHbhasCja1+ZzXo0QHg==',
        created_at: '2024-08-05T16:31:52.754993Z',
      },
      domain_id:
        'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
    },
    {
      contract: {
        template_id:
          'a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.Round:IssuingMiningRound',
        contract_id:
          '00f85500d289d7bca506f53bcde9a70ae758e5a885021ab8a8f9c9dc6b4ed3d047ca101220a2b61b69f91a46c91f722b37fc7cc7483b15290bc499582e18a2e1a44998e248',
        payload: {
          dso: 'DSO::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
          optIssuancePerValidatorFaucetCoupon: '570.0',
          issuancePerFeaturedAppRewardCoupon: '100.0',
          opensAt: '2024-08-05T16:51:55.364121Z',
          issuancePerSvRewardCoupon: '60.8828006088',
          targetClosesAt: '2024-08-05T17:11:55.364121Z',
          issuancePerUnfeaturedAppRewardCoupon: '0.6',
          round: {
            number: '16',
          },
          issuancePerValidatorRewardCoupon: '0.2',
        },
        created_event_blob:
          'CgMyLjESmwQKRQD4VQDSide8pQb1O83ppwrnWOWohQIauKj5ydxrTtPQR8oQEiCithtp+RpGyR9yKzf8fMdIOxUpC8SZWC4YouGkSZjiSBINc3BsaWNlLWFtdWxldBplCkBhMzZlZjg4ODhmYjQ0Y2FhZTEzZDk2MzQxY2UxZmFiZDg0ZmM5ZTJlN2IyMDliYmMzY2FhYmI0OGI2YmUxNjY4EgZTcGxpY2USBVJvdW5kGhJJc3N1aW5nTWluaW5nUm91bmQi2wFq2AEKTQpLOklEU086OjEyMjBmYjg5ZDYyNzc0YmQ1YjNmZDhhMTFjMWIyMmM4YzU0NTNlODI4NmMzY2Y3YWRkNTE1Yzk4ZDdiY2ExOTJlZjE4CgoKCGoGCgQKAhggChAKDjIMMC4yMDAwMDAwMDAwChIKEDIOMTAwLjAwMDAwMDAwMDAKEAoOMgwwLjYwMDAwMDAwMDAKEQoPMg02MC44ODI4MDA2MDg4CgsKCSkZb5x+8h4GAAoLCgkpGfsixvIeBgAKFgoUUhIKEDIONTcwLjAwMDAwMDAwMDAqSURTTzo6MTIyMGZiODlkNjI3NzRiZDViM2ZkOGExMWMxYjIyYzhjNTQ1M2U4Mjg2YzNjZjdhZGQ1MTVjOThkN2JjYTE5MmVmMTg5GSnZWvIeBgBCKgomCiQIARIg2Hzakus2KZFxpDCHlxUyhc9ZBDjX4TO/pyq1Y8GMC74QHg==',
        created_at: '2024-08-05T16:41:55.364121Z',
      },
      domain_id:
        'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
    },
  ],
};
