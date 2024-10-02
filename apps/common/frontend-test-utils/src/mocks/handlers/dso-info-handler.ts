import { rest, RestHandler } from 'msw';

// Obtained via `curl https://sv.sv-2.cimain.network.canton.global/api/sv/v0/dso`
// ...and then npmFix made it look nice.
// You'll need to update this on template changes to DsoRules and AmuletRules.
export const dsoInfo = {
  sv_user: 'OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn@clients',
  sv_party_id:
    'Digital-Asset-2::1220ed548efbcc22bb5097bd5a98303d1d64ab519f9568cdc1676ef1630da1fa6832',
  dso_party_id: 'DSO::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
  voting_threshold: 3,
  latest_mining_round: {
    contract: {
      template_id:
        '218bd1d12914957ff65c2f26f3e752337f10b643b2115af712e287e06dc248ca:Splice.Round:OpenMiningRound',
      contract_id:
        '00c5e96485ac00043b7e0b576faefe6ef597b9a279f07807eea3b18a2789c65e1cca021220ab83da15af4b90ea1042477b33ea68cebd055e07480608c3f96152b1c49f7106',
      payload: {
        issuingFor: {
          microseconds: '450000000',
        },
        issuanceConfig: {
          validatorRewardPercentage: '0.5',
          amuletToIssuePerYear: '40000000000.0',
          unfeaturedAppRewardCap: '0.6',
          appRewardPercentage: '0.15',
          validatorFaucetCap: '2.85',
          featuredAppRewardCap: '100.0',
          validatorRewardCap: '0.2',
        },
        opensAt: '2024-01-09T19:20:43.133736Z',
        transferConfigUsd: {
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
        targetClosesAt: '2024-01-09T19:25:43.133736Z',
        amuletPrice: '1.0',
        tickDuration: {
          microseconds: '150000000',
        },
        dso: 'DSO::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
        round: {
          number: '3',
        },
      },
      created_event_blob:
        'CgNkZXYS8QYKRQDF6WSFrAAEO34LV2+u/m71l7miefB4B+6jsYonicZeHMoCEiCrg9oVr0uQ6hBCR3sz6mjOvQVeB0gGCMP5YVKxxJ9xBhJeCkAyMThiZDFkMTI5MTQ5NTdmZjY1YzJmMjZmM2U3NTIzMzdmMTBiNjQzYjIxMTVhZjcxMmUyODdlMDZkYzI0OGNhEgJDQxIFUm91bmQaD09wZW5NaW5pbmdSb3VuZBrJBArGBBJNEktSSVNWQzo6MTIyMGE1NTVlY2NlZWQ3ZmVmNDQ1YzdlYzMzM2MxNDQ0OWQ5ODFmYjY1OTViZTIxOGM1ZDcwMWVlZjVlYTYzYTFiY2ESChIICgYSBBICKAYSEBIOMgwxLjAwMDAwMDAwMDASCxIJSSgD6jWIDgYAEgsSCUkopstHiA4GABIOEgwKChIIEgYogNKTrQMSiQIShgIKgwISFhIUChISEBIOMgwwLjAzMDAwMDAwMDASFhIUChISEBIOMgwwLjAwMDAwNDgyMjUSpAESoQEKngESEBIOMgwwLjAxMDAwMDAwMDASiQEShgEigwEKKAomEhISEDIOMTAwLjAwMDAwMDAwMDASEBIOMgwwLjAwMTAwMDAwMDAKKQonEhMSETIPMTAwMC4wMDAwMDAwMDAwEhASDjIMMC4wMDAxMDAwMDAwCiwKKhIWEhQyEjEwMDAwMDAuMDAwMDAwMDAwMBIQEg4yDDAuMDAwMDEwMDAwMBIWEhQKEhIQEg4yDDAuMDA1MDAwMDAwMBIFEgMoyAESBRIDKMgBEgQSAihkEpABEo0BCooBEhoSGDIWNDAwMDAwMDAwMDAuMDAwMDAwMDAwMBIQEg4yDDAuNTAwMDAwMDAwMBIQEg4yDDAuMTUwMDAwMDAwMBIQEg4yDDAuMjAwMDAwMDAwMBISEhAyDjEwMC4wMDAwMDAwMDAwEhASDjIMMC42MDAwMDAwMDAwEhASDjIMMi44NTAwMDAwMDAwEg4SDAoKEggSBiiAxoaPASpJU1ZDOjoxMjIwYTU1NWVjY2VlZDdmZWY0NDVjN2VjMzMzYzE0NDQ5ZDk4MWZiNjU5NWJlMjE4YzVkNzAxZWVmNWVhNjNhMWJjYTmoMfksiA4GAEIoCiYKJAgBEiCgTuxgdiWHx5vZWf1A6G5VedSrPWvqJqL4OyX06xBIUw==',
      created_at: '2024-01-09T19:18:13.133736Z',
    },
    domain_id:
      'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
  },
  amulet_rules: {
    contract: {
      template_id:
        '218bd1d12914957ff65c2f26f3e752337f10b643b2115af712e287e06dc248ca:Splice.AmuletRules:AmuletRules',
      contract_id:
        '0084bd1732e6ef1757c2755a83d6acfdc9bf68688b7f55d19d4586008ee3228bceca02122028af9a1d4fff115e10a40adb65d08dcd69463ad6bf5c3055e12d1b960bbad9da',
      payload: {
        dso: 'DSO::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
        configSchedule: {
          initialValue: getAmuletConfig('0.03'),
          futureValues: [
            {
              _1: '2023-03-15T08:35:00Z',
              _2: getAmuletConfig('0.003'),
            },
            {
              _1: '2024-03-15T08:35:00Z',
              _2: getAmuletConfig('4815162342'),
            },
          ],
        },
        isDevNet: true,
      },
      created_event_blob:
        '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',
      created_at: '2024-01-09T19:15:33.960325Z',
    },
    domain_id:
      'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
  },
  dso_rules: {
    contract: {
      template_id:
        'b71a4deb943e8f7f27bb7a384c1b8da8a88f5cd40f92d5b1b56b97f1cb379f27:Splice.DsoRules:DsoRules',
      contract_id:
        '00cb5ebc4580a0806e202a295fff32ac769d4a1ba969c0d83773ae98dc4ff9c246ca021220d0f926a6d088171f7f38abbee000b9e3e6d098d76632e1374e34c8d43b702519',
      payload: {
        epoch: '0',
        initialTrafficState: [
          [
            'MED::mediator::1220919a6e8c9ddd3b07ef36e698e79686dcf5e4c6b32affb57b5a910cc75f7b66b4',
            {
              consumedTraffic: '0',
            },
          ],
          [
            'PAR::participant::1220ed548efbcc22bb5097bd5a98303d1d64ab519f9568cdc1676ef1630da1fa6832',
            {
              consumedTraffic: '0',
            },
          ],
        ],
        offboardedSvs: [],
        config: {
          svOnboardingRequestTimeout: {
            microseconds: '3600000000',
          },
          numUnclaimedRewardsThreshold: '10',
          actionConfirmationTimeout: {
            microseconds: '3600000000',
          },
          dsoDelegateInactiveTimeout: {
            microseconds: '70000000',
          },
          voteRequestTimeout: {
            microseconds: '604800000000',
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
          numMemberTrafficContractsThreshold: '5',
          initialTrafficGrant: '1000000',
          svOnboardingConfirmedTimeout: {
            microseconds: '3600000000',
          },
          decentralizedSynchronizer: {
            synchronizers: [
              [
                'global-synchronizer::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
                {
                  state: 'DS_Operational',
                  cometBftGenesisJson:
                    'TODO(#4900): share CometBFT genesis.json of founding SV node via DsoRules config.',
                  acsCommitmentReconciliationInterval: '1600',
                },
              ],
            ],
            lastSynchronizerId:
              'global-synchronizer::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
            activeSynchronizerId:
              'global-synchronizer::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
          },
          maxTextLength: '1024',
        },
        dsoDelegate:
          'Digital-Asset-2::1220ed548efbcc22bb5097bd5a98303d1d64ab519f9568cdc1676ef1630da1fa6832',
        isDevNet: true,
        svs: [
          [
            'Digital-Asset-2::1220ed548efbcc22bb5097bd5a98303d1d64ab519f9568cdc1676ef1630da1fa6832',
            {
              numRewardCouponsMissed: '0',
              name: 'Digital-Asset-2',
              joinedAsOfRound: {
                number: '0',
              },
              svRewardWeight: '10',
              participantId:
                'PAR::participant-1::1220ed548efbcc22bb5097bd5a98303d1d64ab519f9568cdc1676ef1630da1fa6832',
              synchronizerNodes: [
                [
                  'global-synchronizer::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
                  {
                    cometBft: {
                      nodes: [
                        [
                          '5af57aa83abcec085c949323ed8538108757be9c',
                          {
                            validatorPubKey: 'gpkwc1WCttL8ZATBIPWIBRCrb0eV4JwMCnjRa56REPw=',
                            votingPower: '1',
                          },
                        ],
                      ],
                      governanceKeys: [
                        {
                          pubKey: 'm16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8=',
                        },
                      ],
                      sequencingKeys: [],
                    },
                    sequencer: {
                      migrationId: '0',
                      sequencerId:
                        'SEQ::sequencer::12209f0d96157ae83871bd347d2fe22fe0b982dfbfe50016f7cf6dcfdfcd4eb8e132',
                      url: 'https://sequencer-0.sv-2.cimain.network.canton.global',
                      availableAfter: '2024-01-09T19:15:31.137243Z',
                    },
                    mediator: {
                      mediatorId:
                        'MED::mediator::1220919a6e8c9ddd3b07ef36e698e79686dcf5e4c6b32affb57b5a910cc75f7b66b4',
                    },
                  },
                ],
              ],
              lastReceivedRewardFor: {
                number: '-1',
              },
            },
          ],
          [
            'Digital-Asset-Eng-2::12205ab9210b258422a251d6148b031d71147405948c92bf9c4bc78029c5479aed75',
            {
              numRewardCouponsMissed: '0',
              name: 'Digital-Asset-Eng-2',
              joinedAsOfRound: {
                number: '0',
              },
              svRewardWeight: '12345',
              participantId:
                'PAR::participant-2::12205ab9210b258422a251d6148b031d71147405948c92bf9c4bc78029c5479aed75',
              synchronizerNodes: [
                [
                  'global-synchronizer::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
                  {
                    cometBft: {
                      nodes: [
                        [
                          'c36b3bbd969d993ba0b4809d1f587a3a341f22c1',
                          {
                            validatorPubKey: 'BVSM9/uPGLU7lJj72SUw1a261z2L6Yy2XKLhpUvbxqE=',
                            votingPower: '1',
                          },
                        ],
                      ],
                      governanceKeys: [
                        {
                          pubKey: 'm16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8=',
                        },
                      ],
                      sequencingKeys: [],
                    },
                    sequencer: {
                      migrationId: '0',
                      sequencerId:
                        'SEQ::sequencer::12207eec8b03a668493a6068a755e2eb32084d9b588717049690cdbecb6558fd325c',
                      url: 'https://sequencer-0.sv-2-eng.cimain.network.canton.global',
                      availableAfter: '2024-01-09T19:19:10.755996Z',
                    },
                    mediator: {
                      mediatorId:
                        'MED::mediator::122046b485a233b81f6018831c983a532d125aad8784df8f0c0a9478d66c46292d60',
                    },
                  },
                ],
              ],
              lastReceivedRewardFor: {
                number: '-1',
              },
            },
          ],
          [
            'Digital-Asset-Eng-3::12203cb019c9986425861c91685d9b7c0068cf48abb8dff8e20f166501f7f677dce7',
            {
              numRewardCouponsMissed: '0',
              name: 'Digital-Asset-Eng-3',
              joinedAsOfRound: {
                number: '0',
              },
              svRewardWeight: '12345',
              participantId:
                'PAR::participant-3::12203cb019c9986425861c91685d9b7c0068cf48abb8dff8e20f166501f7f677dce7',
              synchronizerNodes: [
                [
                  'global-synchronizer::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
                  {
                    cometBft: {
                      nodes: [
                        [
                          '0d8e87c54d199e85548ccec123c9d92966ec458c',
                          {
                            validatorPubKey: 'dxm4n1MRP/GuSEkJIwbdB4zVcGAeacohFKNtbKK8oRA=',
                            votingPower: '1',
                          },
                        ],
                      ],
                      governanceKeys: [
                        {
                          pubKey: 'm16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8=',
                        },
                      ],
                      sequencingKeys: [],
                    },
                    sequencer: {
                      migrationId: '0',
                      sequencerId:
                        'SEQ::sequencer::12207a75f3778b60414df3bb876e4a7c84976f35640b4daf30894189bc73e5c7fe38',
                      url: 'https://sequencer-0.sv-3-eng.cimain.network.canton.global',
                      availableAfter: '2024-01-09T19:19:14.361604Z',
                    },
                    mediator: {
                      mediatorId:
                        'MED::mediator::1220ed81687df57e0f6be850bcd546b1751a0b4ad8a8d8c5eeef99ddac8405d49fab',
                    },
                  },
                ],
              ],
              lastReceivedRewardFor: {
                number: '-1',
              },
            },
          ],
          [
            'Digital-Asset-Eng-4::122070fc4bb3519a4f39f5919b5a166e30794733e56ad9fba2157f7208ff458f7dc7',
            {
              numRewardCouponsMissed: '0',
              name: 'Digital-Asset-Eng-4',
              joinedAsOfRound: {
                number: '0',
              },
              svRewardWeight: '12345',
              participantId:
                'PAR::participant-4::122070fc4bb3519a4f39f5919b5a166e30794733e56ad9fba2157f7208ff458f7dc7',
              synchronizerNodes: [
                [
                  'global-synchronizer::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
                  {
                    cometBft: {
                      nodes: [
                        [
                          'ee738517c030b42c3ff626d9f80b41dfc4b1a3b8',
                          {
                            validatorPubKey: '2umZdUS97a6VUXMGsgKJ/VbQbanxWaFUxK1QimhlEjo=',
                            votingPower: '1',
                          },
                        ],
                      ],
                      governanceKeys: [
                        {
                          pubKey: 'm16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8=',
                        },
                      ],
                      sequencingKeys: [],
                    },
                    sequencer: {
                      migrationId: '0',
                      sequencerId:
                        'SEQ::sequencer::12208d47d9361a0e84ee10648c611fc2436885ab5193aa67fa5d9eefc7929f732e96',
                      url: 'https://sequencer-0.sv-4-eng.cimain.network.canton.global',
                      availableAfter: '2024-01-09T19:18:43.522097Z',
                    },
                    mediator: {
                      mediatorId:
                        'MED::mediator::12208c13beecbd916771ce198d9d0f048b243ed99c6e39e34bdec5b32fbb7a51bab4',
                    },
                  },
                ],
              ],
              lastReceivedRewardFor: {
                number: '-1',
              },
            },
          ],
        ],
        dso: 'DSO::1220a555ecceed7fef445c7ec333c14449d981fb6595be218c5d701eef5ea63a1bca',
      },
      created_event_blob:
        'CgNkZXYSnyEKRQDLXrxFgKCAbiAqKV//Mqx2nUobqWnA2DdzrpjcT/nCRsoCEiDQ+Sam0IgXH384q77gALnj5tCY12Yy4TdONMjUO3AlGRJaCkBiNzFhNGRlYjk0M2U4ZjdmMjdiYjdhMzg0YzFiOGRhOGE4OGY1Y2Q0MGY5MmQ1YjFiNTZiOTdmMWNiMzc5ZjI3EgJDThIIU3ZjUnVsZXMaCFN2Y1J1bGVzGvseCvgeEk0SS1JJU1ZDOjoxMjIwYTU1NWVjY2VlZDdmZWY0NDVjN2VjMzMzYzE0NDQ5ZDk4MWZiNjU5NWJlMjE4YzVkNzAxZWVmNWVhNjNhMWJjYRIEEgIoABKhFxKeF5IBmhcK4gUKW1JZQ2FudG9uLUZvdW5kYXRpb24tMTo6MTIyMGVkNTQ4ZWZiY2MyMmJiNTA5N2JkNWE5ODMwM2QxZDY0YWI1MTlmOTU2OGNkYzE2NzZlZjE2MzBkYTFmYTY4MzISggUK/wQSFxIVQhNDYW50b24tRm91bmRhdGlvbi0xEgoSCAoGEgQSAigAEgoSCAoGEgQSAigBEgQSAigAEgQSAigUEr8EErwEkgG4BAq1BApVQlNnbG9iYWwtZG9tYWluOjoxMjIwYTU1NWVjY2VlZDdmZWY0NDVjN2VjMzMzYzE0NDQ5ZDk4MWZiNjU5NWJlMjE4YzVkNzAxZWVmNWVhNjNhMWJjYRLbAwrYAxK5ARK2AQqzARJvEm2SAWoKaAoqQig1YWY1N2FhODNhYmNlYzA4NWM5NDkzMjNlZDg1MzgxMDg3NTdiZTljEjoKOBIwEi5CLGdwa3djMVdDdHRMOFpBVEJJUFdJQlJDcmIwZVY0SndNQ25qUmE1NlJFUHc9EgQSAigCEjoSOCI2CjQKMhIwEi5CLG0xNmhhTHp2L2QvT2swNFNtMzlBQmswZjBIc1NXWU5aeHJJVWl5UStjSzg9EgQSAiIAErYBErMBcrABCq0BCqoBElgSVkJUU0VROjpzZXF1ZW5jZXI6OjEyMjA5ZjBkOTYxNTdhZTgzODcxYmQzNDdkMmZlMjJmZTBiOTgyZGZiZmU1MDAxNmY3Y2Y2ZGNmZGZjZDRlYjhlMTMyEj0SO0I5aHR0cHM6Ly9zZXF1ZW5jZXItMC5zdi0xLnN2Yy5jaW1haW4ubmV0d29yay5jYW50b24uZ2xvYmFsEg8SDXILCglJ21JRI4gOBgASYRJfcl0KWwpZElcSVUJTTUVEOjptZWRpYXRvcjo6MTIyMDkxOWE2ZThjOWRkZDNiMDdlZjM2ZTY5OGU3OTY4NmRjZjVlNGM2YjMyYWZmYjU3YjVhOTEwY2M3NWY3YjY2YjQK5AUKW1JZQ2FudG9uLUZvdW5kYXRpb24tMjo6MTIyMDVhYjkyMTBiMjU4NDIyYTI1MWQ2MTQ4YjAzMWQ3MTE0NzQwNTk0OGM5MmJmOWM0YmM3ODAyOWM1NDc5YWVkNzUShAUKgQUSFxIVQhNDYW50b24tRm91bmRhdGlvbi0yEgoSCAoGEgQSAigAEgoSCAoGEgQSAigBEgQSAigAEgYSBCjywAESvwQSvASSAbgECrUEClVCU2dsb2JhbC1kb21haW46OjEyMjBhNTU1ZWNjZWVkN2ZlZjQ0NWM3ZWMzMzNjMTQ0NDlkOTgxZmI2NTk1YmUyMThjNWQ3MDFlZWY1ZWE2M2ExYmNhEtsDCtgDErkBErYBCrMBEm8SbZIBagpoCipCKGMzNmIzYmJkOTY5ZDk5M2JhMGI0ODA5ZDFmNTg3YTNhMzQxZjIyYzESOgo4EjASLkIsQlZTTTkvdVBHTFU3bEpqNzJTVXcxYTI2MXoyTDZZeTJYS0xocFV2YnhxRT0SBBICKAISOhI4IjYKNAoyEjASLkIsbTE2aGFMenYvZC9PazA0U20zOUFCazBmMEhzU1dZTlp4cklVaXlRK2NLOD0SBBICIgAStgESswFysAEKrQEKqgESWBJWQlRTRVE6OnNlcXVlbmNlcjo6MTIyMDdlZWM4YjAzYTY2ODQ5M2E2MDY4YTc1NWUyZWIzMjA4NGQ5YjU4ODcxNzA0OTY5MGNkYmVjYjY1NThmZDMyNWMSPRI7QjlodHRwczovL3NlcXVlbmNlci0wLnN2LTIuc3ZjLmNpbWFpbi5uZXR3b3JrLmNhbnRvbi5nbG9iYWwSDxINcgsKCUmccGgwiA4GABJhEl9yXQpbClkSVxJVQlNNRUQ6Om1lZGlhdG9yOjoxMjIwNDZiNDg1YTIzM2I4MWY2MDE4ODMxYzk4M2E1MzJkMTI1YWFkODc4NGRmOGYwYzBhOTQ3OGQ2NmM0NjI5MmQ2MArkBQpbUllDYW50b24tRm91bmRhdGlvbi0zOjoxMjIwM2NiMDE5Yzk5ODY0MjU4NjFjOTE2ODVkOWI3YzAwNjhjZjQ4YWJiOGRmZjhlMjBmMTY2NTAxZjdmNjc3ZGNlNxKEBQqBBRIXEhVCE0NhbnRvbi1Gb3VuZGF0aW9uLTMSChIICgYSBBICKAASChIICgYSBBICKAESBBICKAASBhIEKPLAARK/BBK8BJIBuAQKtQQKVUJTZ2xvYmFsLWRvbWFpbjo6MTIyMGE1NTVlY2NlZWQ3ZmVmNDQ1YzdlYzMzM2MxNDQ0OWQ5ODFmYjY1OTViZTIxOGM1ZDcwMWVlZjVlYTYzYTFiY2ES2wMK2AMSuQEStgEKswESbxJtkgFqCmgKKkIoMGQ4ZTg3YzU0ZDE5OWU4NTU0OGNjZWMxMjNjOWQ5Mjk2NmVjNDU4YxI6CjgSMBIuQixkeG00bjFNUlAvR3VTRWtKSXdiZEI0elZjR0FlYWNvaEZLTnRiS0s4b1JBPRIEEgIoAhI6EjgiNgo0CjISMBIuQixtMTZoYUx6di9kL09rMDRTbTM5QUJrMGYwSHNTV1lOWnhySVVpeVErY0s4PRIEEgIiABK2ARKzAXKwAQqtAQqqARJYElZCVFNFUTo6c2VxdWVuY2VyOjoxMjIwN2E3NWYzNzc4YjYwNDE0ZGYzYmI4NzZlNGE3Yzg0OTc2ZjM1NjQwYjRkYWYzMDg5NDE4OWJjNzNlNWM3ZmUzOBI9EjtCOWh0dHBzOi8vc2VxdWVuY2VyLTAuc3YtMy5zdmMuY2ltYWluLm5ldHdvcmsuY2FudG9uLmdsb2JhbBIPEg1yCwoJSQR1nzCIDgYAEmESX3JdClsKWRJXElVCU01FRDo6bWVkaWF0b3I6OjEyMjBlZDgxNjg3ZGY1N2UwZjZiZTg1MGJjZDU0NmIxNzUxYTBiNGFkOGE4ZDhjNWVlZWY5OWRkYWM4NDA1ZDQ5ZmFiCuQFCltSWUNhbnRvbi1Gb3VuZGF0aW9uLTQ6OjEyMjA3MGZjNGJiMzUxOWE0ZjM5ZjU5MTliNWExNjZlMzA3OTQ3MzNlNTZhZDlmYmEyMTU3ZjcyMDhmZjQ1OGY3ZGM3EoQFCoEFEhcSFUITQ2FudG9uLUZvdW5kYXRpb24tNBIKEggKBhIEEgIoABIKEggKBhIEEgIoARIEEgIoABIGEgQo8sABEr8EErwEkgG4BAq1BApVQlNnbG9iYWwtZG9tYWluOjoxMjIwYTU1NWVjY2VlZDdmZWY0NDVjN2VjMzMzYzE0NDQ5ZDk4MWZiNjU5NWJlMjE4YzVkNzAxZWVmNWVhNjNhMWJjYRLbAwrYAxK5ARK2AQqzARJvEm2SAWoKaAoqQihlZTczODUxN2MwMzBiNDJjM2ZmNjI2ZDlmODBiNDFkZmM0YjFhM2I4EjoKOBIwEi5CLDJ1bVpkVVM5N2E2VlVYTUdzZ0tKL1ZiUWJhbnhXYUZVeEsxUWltaGxFam89EgQSAigCEjoSOCI2CjQKMhIwEi5CLG0xNmhhTHp2L2QvT2swNFNtMzlBQmswZjBIc1NXWU5aeHJJVWl5UStjSzg9EgQSAiIAErYBErMBcrABCq0BCqoBElgSVkJUU0VROjpzZXF1ZW5jZXI6OjEyMjA4ZDQ3ZDkzNjFhMGU4NGVlMTA2NDhjNjExZmMyNDM2ODg1YWI1MTkzYWE2N2ZhNWQ5ZWVmYzc5MjlmNzMyZTk2Ej0SO0I5aHR0cHM6Ly9zZXF1ZW5jZXItMC5zdi00LnN2Yy5jaW1haW4ubmV0d29yay5jYW50b24uZ2xvYmFsEg8SDXILCglJMeLILogOBgASYRJfcl0KWwpZElcSVUJTTUVEOjptZWRpYXRvcjo6MTIyMDhjMTNiZWVjYmQ5MTY3NzFjZTE5OGQ5ZDBmMDQ4YjI0M2VkOTljNmUzOWUzNGJkZWM1YjMyZmJiN2E1MWJhYjQSBRIDkgEAEl0SW1JZQ2FudG9uLUZvdW5kYXRpb24tMTo6MTIyMGVkNTQ4ZWZiY2MyMmJiNTA5N2JkNWE5ODMwM2QxZDY0YWI1MTlmOTU2OGNkYzE2NzZlZjE2MzBkYTFmYTY4MzISvQQSugQKtwQSBBICKBQSBBICKAoSDhIMCgoSCBIGKICMjZ4CEg4SDAoKEggSBiiAkJ3pGhIOEgwKChIIEgYogJCd6RoSDxINCgsSCRIHKICAnY6aIxINEgsKCRIHEgUogPbgQhIpEicKJRIjEiEKHxIEEgIoBBIEEgIoBBIEEgIoBBIEEgIoZBIFEgMogAQSBRIDKIAQEgYSBCiAiXoSDhIMCgoSCBIGKIDIzrQNEo4DEosDCogDEtMBEtABkgHMAQrJAQpVQlNnbG9iYWwtZG9tYWluOjoxMjIwYTU1NWVjY2VlZDdmZWY0NDVjN2VjMzMzYzE0NDQ5ZDk4MWZiNjU5NWJlMjE4YzVkNzAxZWVmNWVhNjNhMWJjYRJwCm4SFRITigEQEg5EU19PcGVyYXRpb25hbBJVElNCUVRPRE8oIzQ5MDApOiBzaGFyZSBDb21ldEJGVCBnZW5lc2lzLmpzb24gb2YgZm91bmRpbmcgU1Ygbm9kZSB2aWEgU3ZjUnVsZXMgY29uZmlnLhJXElVCU2dsb2JhbC1kb21haW46OjEyMjBhNTU1ZWNjZWVkN2ZlZjQ0NWM3ZWMzMzNjMTQ0NDlkOTgxZmI2NTk1YmUyMThjNWQ3MDFlZWY1ZWE2M2ExYmNhElcSVUJTZ2xvYmFsLWRvbWFpbjo6MTIyMGE1NTVlY2NlZWQ3ZmVmNDQ1YzdlYzMzM2MxNDQ0OWQ5ODFmYjY1OTViZTIxOGM1ZDcwMWVlZjVlYTYzYTFiY2ES0AESzQGSAckBCmEKVUJTTUVEOjptZWRpYXRvcjo6MTIyMDkxOWE2ZThjOWRkZDNiMDdlZjM2ZTY5OGU3OTY4NmRjZjVlNGM2YjMyYWZmYjU3YjVhOTEwY2M3NWY3YjY2YjQSCAoGEgQSAigACmQKWEJWUEFSOjpwYXJ0aWNpcGFudDo6MTIyMGVkNTQ4ZWZiY2MyMmJiNTA5N2JkNWE5ODMwM2QxZDY0YWI1MTlmOTU2OGNkYzE2NzZlZjE2MzBkYTFmYTY4MzISCAoGEgQSAigAEgQSAlgBKklTVkM6OjEyMjBhNTU1ZWNjZWVkN2ZlZjQ0NWM3ZWMzMzNjMTQ0NDlkOTgxZmI2NTk1YmUyMThjNWQ3MDFlZWY1ZWE2M2ExYmNhOdM1DC2IDgYAQigKJgokCAESIPKYoAaliKrlh2j2l0JzyRfKBAw67ACJ8Gt89rH9+3cc',
      created_at: '2024-01-09T19:18:14.379987Z',
    },
    domain_id:
      'global-domain::1220fb89d62774bd5b3fd8a11c1b22c8c5453e8286c3cf7add515c98d7bca192ef18',
  },
  sv_node_states: [], // TODO(tech-debt): add better mock data
};

// eslint-disable-next-line @typescript-eslint/explicit-module-boundary-types
export function getAmuletConfig(createFee: string) {
  return {
    packageConfig: {
      amuletNameService: '0.1.5',
      walletPayments: '0.1.5',
      dsoGovernance: '0.1.8',
      validatorLifecycle: '0.1.1',
      amulet: '0.1.5',
      wallet: '0.1.5',
    },
    tickDuration: {
      microseconds: '600000000',
    },
    decentralizedSynchronizer: {
      requiredSynchronizers: {
        map: [
          [
            'global-domain::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
            {},
          ],
        ],
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
        fee: createFee,
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
          },
        },
      ],
    },
  };
}

export function dsoInfoHandler(baseUrl: string): RestHandler {
  return rest.get(`${baseUrl}/v0/dso`, (_, res, ctx) => {
    return res(ctx.json(dsoInfo));
  });
}
