// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  validatorLicensesHandler,
  dsoInfoHandler,
} from '@lfdecentralizedtrust/splice-common-test-handlers';
import { RestHandler, rest } from 'msw';
import {
  ErrorResponse,
  GetAmuletRulesResponse,
  GetRewardsCollectedResponse,
  GetRoundOfLatestDataResponse,
  GetDsoPartyIdResponse,
  ListActivityResponse,
  LookupEntryByPartyResponse,
  GetOpenAndIssuingMiningRoundsResponse,
  FeatureSupportResponse,
} from '@lfdecentralizedtrust/scan-openapi';

import { config } from '../../setup/config';
import { getAmuletRulesResponse } from '../data';
import { Instrument } from '@lfdecentralizedtrust/token-metadata-openapi';

const amuletNameServiceAcronym = config.spliceInstanceNames.nameServiceNameAcronym;

export const buildScanMock = (baseScanUrl: string): RestHandler[] => {
  const scanUrl = `${baseScanUrl}/api/scan`;
  return [
    rest.get(`${scanUrl}/v0/feature-support`, (_, res, ctx) => {
      return res(ctx.json<FeatureSupportResponse>({ no_holding_fees_on_transfers: false }));
    }),

    dsoInfoHandler(scanUrl),
    rest.get(`${scanUrl}/v0/dso-party-id`, (_, res, ctx) => {
      return res(
        ctx.json<GetDsoPartyIdResponse>({
          dso_party_id: 'DSO::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
        })
      );
    }),

    rest.get(`${baseScanUrl}/registry/metadata/v1/instruments/Amulet`, (_, res, ctx) => {
      return res(
        ctx.json<Instrument>({
          id: 'Amulet',
          name: 'Amulet',
          symbol: 'AMT',
          totalSupply: '123',
          totalSupplyAsOf: new Date(),
          decimals: 10,
          supportedApis: {},
        })
      );
    }),

    rest.post(`${scanUrl}/v0/open-and-issuing-mining-rounds`, (_, res, ctx) => {
      return res(
        ctx.json<GetOpenAndIssuingMiningRoundsResponse>({
          time_to_live_in_microseconds: 600000000,
          open_mining_rounds: {
            '009a934375bd5cc210ef43bac9f39c30e0217508352736292e0c9a01457bced1b8ca101220a31c4383e5b082d5db639bb177c4827796043b896c84cab370412b33a3ca1a21':
              {
                contract: {
                  template_id:
                    '4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.Round:OpenMiningRound',
                  contract_id:
                    '009a934375bd5cc210ef43bac9f39c30e0217508352736292e0c9a01457bced1b8ca101220a31c4383e5b082d5db639bb177c4827796043b896c84cab370412b33a3ca1a21',
                  payload: {
                    dso: 'DSO::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
                    tickDuration: {
                      microseconds: '600000000',
                    },
                    issuingFor: {
                      microseconds: '7800000000',
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
                    opensAt: '2025-02-03T13:43:37.895871Z',
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
                    targetClosesAt: '2025-02-03T14:03:37.895871Z',
                    round: {
                      number: '13',
                    },
                  },
                  created_event_blob:
                    'CgMyLjESnAcKRQCak0N1vVzCEO9DusnznDDgIXUINSc2KS4MmgFFe87RuMoQEiCjHEOD5bCC1dtjm7F3xIJ3lgQ7iWyEyrNwQSszo8oaIRINc3BsaWNlLWFtdWxldBpiCkA0NjQ2ZDUwY2JkZWM2ZjA4OGM5OGFlNTQzZGE1Yzk3M2QyZDFiZTMzNjNiOWYzMmViMDk3ZDhmZGMwNjNhZGU3EgZTcGxpY2USBVJvdW5kGg9PcGVuTWluaW5nUm91bmQi3wRq3AQKTQpLOklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1CgoKCGoGCgQKAhgaChAKDjIMMC4wMDUwMDAwMDAwCgsKCSm//1gUPS0GAAoLCgkpv4vfWz0tBgAKDgoMagoKCAoGGIC41I46CpsCCpgCapUCChYKFGoSChAKDjIMMC4wMzAwMDAwMDAwChYKFGoSChAKDjIMMC4wMDAwMTkwMjU5CqQBCqEBap4BChAKDjIMMC4wMTAwMDAwMDAwCokBCoYBWoMBCihqJgoSChAyDjEwMC4wMDAwMDAwMDAwChAKDjIMMC4wMDEwMDAwMDAwCilqJwoTChEyDzEwMDAuMDAwMDAwMDAwMAoQCg4yDDAuMDAwMTAwMDAwMAosaioKFgoUMhIxMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjAwMDAxMDAwMDAKFgoUahIKEAoOMgwwLjAwNTAwMDAwMDAKEAoOMgwxLjAwMDAwMDAwMDAKBQoDGMgBCgUKAxjIAQoECgIYZAqUAQqRAWqOAQoaChgyFjQwMDAwMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjA1MDAwMDAwMDAKEAoOMgwwLjE1MDAwMDAwMDAKEAoOMgwwLjIwMDAwMDAwMDAKEgoQMg4xMDAuMDAwMDAwMDAwMAoQCg4yDDAuNjAwMDAwMDAwMAoUChJSEAoOMgwyLjg1MDAwMDAwMDAKDgoMagoKCAoGGICYmrwEKklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1Ob+5lfA8LQYAQioKJgokCAESII1Y253JV9bGtIGPHgORo5ifHeKP/DyNl1FPeuMkEHhVEB4=',
                  created_at: '2025-02-03T13:33:37.895871Z',
                },
                domain_id:
                  'global-domain::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
              },
            '00b8d18fce8ca1877853768fa3bf54054b960f76a85e2384d446ccdc5d7291ce3dca101220b0d8404b000a47a9b3ad0c5bab2b033ce85aeeb975844bafffb96f51209a3280':
              {
                contract: {
                  template_id:
                    '4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.Round:OpenMiningRound',
                  contract_id:
                    '00b8d18fce8ca1877853768fa3bf54054b960f76a85e2384d446ccdc5d7291ce3dca101220b0d8404b000a47a9b3ad0c5bab2b033ce85aeeb975844bafffb96f51209a3280',
                  payload: {
                    dso: 'DSO::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
                    tickDuration: {
                      microseconds: '600000000',
                    },
                    issuingFor: {
                      microseconds: '8400000000',
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
                    opensAt: '2025-02-03T13:54:09.430298Z',
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
                    targetClosesAt: '2025-02-03T14:14:09.430298Z',
                    round: {
                      number: '14',
                    },
                  },
                  created_event_blob:
                    'CgMyLjESnAcKRQC40Y/OjKGHeFN2j6O/VAVLlg92qF4jhNRGzNxdcpHOPcoQEiCw2EBLAApHqbOtDFurKwM86FruuXWES6//uW9RIJoygBINc3BsaWNlLWFtdWxldBpiCkA0NjQ2ZDUwY2JkZWM2ZjA4OGM5OGFlNTQzZGE1Yzk3M2QyZDFiZTMzNjNiOWYzMmViMDk3ZDhmZGMwNjNhZGU3EgZTcGxpY2USBVJvdW5kGg9PcGVuTWluaW5nUm91bmQi3wRq3AQKTQpLOklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1CgoKCGoGCgQKAhgcChAKDjIMMC4wMDUwMDAwMDAwCgsKCSkac/05PS0GAAoLCgkpGv+DgT0tBgAKDgoMagoKCAoGGIDQ7so+CpsCCpgCapUCChYKFGoSChAKDjIMMC4wMzAwMDAwMDAwChYKFGoSChAKDjIMMC4wMDAwMTkwMjU5CqQBCqEBap4BChAKDjIMMC4wMTAwMDAwMDAwCokBCoYBWoMBCihqJgoSChAyDjEwMC4wMDAwMDAwMDAwChAKDjIMMC4wMDEwMDAwMDAwCilqJwoTChEyDzEwMDAuMDAwMDAwMDAwMAoQCg4yDDAuMDAwMTAwMDAwMAosaioKFgoUMhIxMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjAwMDAxMDAwMDAKFgoUahIKEAoOMgwwLjAwNTAwMDAwMDAKEAoOMgwxLjAwMDAwMDAwMDAKBQoDGMgBCgUKAxjIAQoECgIYZAqUAQqRAWqOAQoaChgyFjQwMDAwMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjA1MDAwMDAwMDAKEAoOMgwwLjE1MDAwMDAwMDAKEAoOMgwwLjIwMDAwMDAwMDAKEgoQMg4xMDAuMDAwMDAwMDAwMAoQCg4yDDAuNjAwMDAwMDAwMAoUChJSEAoOMgwyLjg1MDAwMDAwMDAKDgoMagoKCAoGGICYmrwEKklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1ORotOhY9LQYAQioKJgokCAESIGnxQ/xls3iV03z1c/PyqX/D+pEGpVzy0O2313jozuBcEB4=',
                  created_at: '2025-02-03T13:44:09.430298Z',
                },
                domain_id:
                  'global-domain::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
              },
            '00c57f207d5cba837f5d5c2c5d98cb39d8e00a5a8722d3d686b95d056e3563f69bca1012201ef22e3a580e785ac39844c13365083ce8e888b7335cec0e480945e13edb5312':
              {
                contract: {
                  template_id:
                    '4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.Round:OpenMiningRound',
                  contract_id:
                    '00c57f207d5cba837f5d5c2c5d98cb39d8e00a5a8722d3d686b95d056e3563f69bca1012201ef22e3a580e785ac39844c13365083ce8e888b7335cec0e480945e13edb5312',
                  payload: {
                    dso: 'DSO::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
                    tickDuration: {
                      microseconds: '600000000',
                    },
                    issuingFor: {
                      microseconds: '9000000000',
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
                    opensAt: '2025-02-03T14:04:37.024279Z',
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
                    targetClosesAt: '2025-02-03T14:24:37.024279Z',
                    round: {
                      number: '15',
                    },
                  },
                  created_event_blob:
                    'CgMyLjESnAcKRQDFfyB9XLqDf11cLF2YyznY4ApahyLT1oa5XQVuNWP2m8oQEiAe8i46WA54WsOYRMEzZQg86OiItzNc7A5ICUXhPttTEhINc3BsaWNlLWFtdWxldBpiCkA0NjQ2ZDUwY2JkZWM2ZjA4OGM5OGFlNTQzZGE1Yzk3M2QyZDFiZTMzNjNiOWYzMmViMDk3ZDhmZGMwNjNhZGU3EgZTcGxpY2USBVJvdW5kGg9PcGVuTWluaW5nUm91bmQi3wRq3AQKTQpLOklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1CgoKCGoGCgQKAhgeChAKDjIMMC4wMDUwMDAwMDAwCgsKCSkXxmVfPS0GAAoLCgkpF1Lspj0tBgAKDgoMagoKCAoGGIDoiIdDCpsCCpgCapUCChYKFGoSChAKDjIMMC4wMzAwMDAwMDAwChYKFGoSChAKDjIMMC4wMDAwMTkwMjU5CqQBCqEBap4BChAKDjIMMC4wMTAwMDAwMDAwCokBCoYBWoMBCihqJgoSChAyDjEwMC4wMDAwMDAwMDAwChAKDjIMMC4wMDEwMDAwMDAwCilqJwoTChEyDzEwMDAuMDAwMDAwMDAwMAoQCg4yDDAuMDAwMTAwMDAwMAosaioKFgoUMhIxMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjAwMDAxMDAwMDAKFgoUahIKEAoOMgwwLjAwNTAwMDAwMDAKEAoOMgwxLjAwMDAwMDAwMDAKBQoDGMgBCgUKAxjIAQoECgIYZAqUAQqRAWqOAQoaChgyFjQwMDAwMDAwMDAwLjAwMDAwMDAwMDAKEAoOMgwwLjA1MDAwMDAwMDAKEAoOMgwwLjE1MDAwMDAwMDAKEAoOMgwwLjIwMDAwMDAwMDAKEgoQMg4xMDAuMDAwMDAwMDAwMAoQCg4yDDAuNjAwMDAwMDAwMAoUChJSEAoOMgwyLjg1MDAwMDAwMDAKDgoMagoKCAoGGICYmrwEKklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1OReAojs9LQYAQioKJgokCAESINmB+Ya7MKDFsouNqAfzkCN1IsjBN+j1D24h8GE6/KQaEB4=',
                  created_at: '2025-02-03T13:54:37.024279Z',
                },
                domain_id:
                  'global-domain::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
              },
          },
          issuing_mining_rounds: {
            '0072433d5d987da2d3e24e87b9e2de0b1acdee81a111654ae99f2d8711fd9c9cc8ca1012204eb8c7877fe0490cacad4e31d5e96345d492c865664f75c0bb090b5b6f4c4412':
              {
                contract: {
                  template_id:
                    '4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.Round:IssuingMiningRound',
                  contract_id:
                    '0072433d5d987da2d3e24e87b9e2de0b1acdee81a111654ae99f2d8711fd9c9cc8ca1012204eb8c7877fe0490cacad4e31d5e96345d492c865664f75c0bb090b5b6f4c4412',
                  payload: {
                    dso: 'DSO::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
                    optIssuancePerValidatorFaucetCoupon: '570.0',
                    issuancePerFeaturedAppRewardCoupon: '100.0',
                    opensAt: '2025-02-03T13:43:53.180125Z',
                    issuancePerSvRewardCoupon: '30.4414003044',
                    targetClosesAt: '2025-02-03T14:03:53.180125Z',
                    issuancePerUnfeaturedAppRewardCoupon: '0.6',
                    round: {
                      number: '10',
                    },
                    issuancePerValidatorRewardCoupon: '0.2',
                  },
                  created_event_blob:
                    'CgMyLjESmwQKRQByQz1dmH2i0+JOh7ni3gsaze6BoRFlSumfLYcR/ZycyMoQEiBOuMeHf+BJDKytTjHV6WNF1JLIZWZPdcC7CQtbb0xEEhINc3BsaWNlLWFtdWxldBplCkA0NjQ2ZDUwY2JkZWM2ZjA4OGM5OGFlNTQzZGE1Yzk3M2QyZDFiZTMzNjNiOWYzMmViMDk3ZDhmZGMwNjNhZGU3EgZTcGxpY2USBVJvdW5kGhJJc3N1aW5nTWluaW5nUm91bmQi2wFq2AEKTQpLOklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1CgoKCGoGCgQKAhgUChAKDjIMMC4yMDAwMDAwMDAwChIKEDIOMTAwLjAwMDAwMDAwMDAKEAoOMgwwLjYwMDAwMDAwMDAKEQoPMg0zMC40NDE0MDAzMDQ0CgsKCSndN0IVPS0GAAoLCgkp3cPIXD0tBgAKFgoUUhIKEDIONTcwLjAwMDAwMDAwMDAqSURTTzo6MTIyMDIzZDg0ZWI0MDE5ZWE3NzVmMzY0NTU0YzM1NThjYTc5ZDQyZGUxMjk4ODRhZGNmMWEyNTk4MWNlOGZkZmZhZTU53fF+8TwtBgBCKgomCiQIARIgsKDXx/J5/37sWqsgLM30Alfzt09dqWCFmp3HzTZItL8QHg==',
                  created_at: '2025-02-03T13:33:53.180125Z',
                },
                domain_id:
                  'global-domain::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
              },
            '00bdacaa0cb7c92062e4d8802864e45053b8ab7416d244eaa3fcae9e5d872c0a18ca101220de0fe5f976df9b62050353ac5380b1f3ba0bc40a9dabb5d5501e49ca16560d41':
              {
                contract: {
                  template_id:
                    '4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.Round:IssuingMiningRound',
                  contract_id:
                    '00bdacaa0cb7c92062e4d8802864e45053b8ab7416d244eaa3fcae9e5d872c0a18ca101220de0fe5f976df9b62050353ac5380b1f3ba0bc40a9dabb5d5501e49ca16560d41',
                  payload: {
                    dso: 'DSO::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
                    optIssuancePerValidatorFaucetCoupon: '570.0',
                    issuancePerFeaturedAppRewardCoupon: '100.0',
                    opensAt: '2025-02-03T13:54:24.945605Z',
                    issuancePerSvRewardCoupon: '30.4414003044',
                    targetClosesAt: '2025-02-03T14:14:24.945605Z',
                    issuancePerUnfeaturedAppRewardCoupon: '0.6',
                    round: {
                      number: '11',
                    },
                    issuancePerValidatorRewardCoupon: '0.2',
                  },
                  created_event_blob:
                    'CgMyLjESmwQKRQC9rKoMt8kgYuTYgChk5FBTuKt0FtJE6qP8rp5dhywKGMoQEiDeD+X5dt+bYgUDU6xTgLHzugvECp2rtdVQHknKFlYNQRINc3BsaWNlLWFtdWxldBplCkA0NjQ2ZDUwY2JkZWM2ZjA4OGM5OGFlNTQzZGE1Yzk3M2QyZDFiZTMzNjNiOWYzMmViMDk3ZDhmZGMwNjNhZGU3EgZTcGxpY2USBVJvdW5kGhJJc3N1aW5nTWluaW5nUm91bmQi2wFq2AEKTQpLOklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1CgoKCGoGCgQKAhgWChAKDjIMMC4yMDAwMDAwMDAwChIKEDIOMTAwLjAwMDAwMDAwMDAKEAoOMgwwLjYwMDAwMDAwMDAKEQoPMg0zMC40NDE0MDAzMDQ0CgsKCSnFMeo6PS0GAAoLCgkpxb1wgj0tBgAKFgoUUhIKEDIONTcwLjAwMDAwMDAwMDAqSURTTzo6MTIyMDIzZDg0ZWI0MDE5ZWE3NzVmMzY0NTU0YzM1NThjYTc5ZDQyZGUxMjk4ODRhZGNmMWEyNTk4MWNlOGZkZmZhZTU5xesmFz0tBgBCKgomCiQIARIgCj2GvEkVQONyQjgx1nPbqFpVGinA5bE6t/CfhjQiyV8QHg==',
                  created_at: '2025-02-03T13:44:24.945605Z',
                },
                domain_id:
                  'global-domain::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
              },
            '00299522d1821229bd7c34fc327002efe2dccdb0f589b74208bbd92563411a937fca1012203ef49e67a05ca824e7109fbf4fce3a49ce1b6a556fea9c292316509840bb1156':
              {
                contract: {
                  template_id:
                    '4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.Round:IssuingMiningRound',
                  contract_id:
                    '00299522d1821229bd7c34fc327002efe2dccdb0f589b74208bbd92563411a937fca1012203ef49e67a05ca824e7109fbf4fce3a49ce1b6a556fea9c292316509840bb1156',
                  payload: {
                    dso: 'DSO::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
                    optIssuancePerValidatorFaucetCoupon: '570.0',
                    issuancePerFeaturedAppRewardCoupon: '100.0',
                    opensAt: '2025-02-03T14:04:54.415965Z',
                    issuancePerSvRewardCoupon: '30.4414003044',
                    targetClosesAt: '2025-02-03T14:24:54.415965Z',
                    issuancePerUnfeaturedAppRewardCoupon: '0.6',
                    round: {
                      number: '12',
                    },
                    issuancePerValidatorRewardCoupon: '0.2',
                  },
                  created_event_blob:
                    'CgMyLjESmwQKRQAplSLRghIpvXw0/DJwAu/i3M2w9Ym3Qgi72SVjQRqTf8oQEiA+9J5noFyoJOcQn79PzjpJzhtqVW/qnCkjFlCYQLsRVhINc3BsaWNlLWFtdWxldBplCkA0NjQ2ZDUwY2JkZWM2ZjA4OGM5OGFlNTQzZGE1Yzk3M2QyZDFiZTMzNjNiOWYzMmViMDk3ZDhmZGMwNjNhZGU3EgZTcGxpY2USBVJvdW5kGhJJc3N1aW5nTWluaW5nUm91bmQi2wFq2AEKTQpLOklEU086OjEyMjAyM2Q4NGViNDAxOWVhNzc1ZjM2NDU1NGMzNTU4Y2E3OWQ0MmRlMTI5ODg0YWRjZjFhMjU5ODFjZThmZGZmYWU1CgoKCGoGCgQKAhgYChAKDjIMMC4yMDAwMDAwMDAwChIKEDIOMTAwLjAwMDAwMDAwMDAKEAoOMgwwLjYwMDAwMDAwMDAKEQoPMg0zMC40NDE0MDAzMDQ0CgsKCSldJm9gPS0GAAoLCgkpXbL1pz0tBgAKFgoUUhIKEDIONTcwLjAwMDAwMDAwMDAqSURTTzo6MTIyMDIzZDg0ZWI0MDE5ZWE3NzVmMzY0NTU0YzM1NThjYTc5ZDQyZGUxMjk4ODRhZGNmMWEyNTk4MWNlOGZkZmZhZTU5XeCrPD0tBgBCKgomCiQIARIgSQFlf26Y19qiziq/8Wl9fkGKd9VqHSK5u3EMy7rZZf4QHg==',
                  created_at: '2025-02-03T13:54:54.415965Z',
                },
                domain_id:
                  'global-domain::122023d84eb4019ea775f364554c3558ca79d42de129884adcf1a25981ce8fdffae5',
              },
          },
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
    rest.post(`${scanUrl}/v0/amulet-rules`, (_, res, ctx) => {
      return res(ctx.json<GetAmuletRulesResponse>(getAmuletRulesResponse(true)));
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
    rest.all('*', req => {
      console.error(JSON.stringify(req));
    }),
  ];
};
