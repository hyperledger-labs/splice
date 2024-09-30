// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as jtv from '@mojotech/json-type-validation';
import { dsoInfo } from 'common-test-utils';
import { ListDsoRulesVoteResultsResponse } from 'sv-openapi';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

// Static constants for mock values

export const voteResults: ListDsoRulesVoteResultsResponse = {
  dso_rules_vote_results: [
    {
      request: {
        dso: 'DSO::122013fe9b84dfc756163484f071da40f5605c2ab9d93eb647052c15e360acce1347',
        requester: 'Digital-Asset-2',
        action: {
          tag: 'ARC_AmuletRules',
          value: {
            amuletRulesAction: {
              tag: 'CRARC_AddFutureAmuletConfigSchedule',
              value: {
                newScheduleItem: {
                  _1: '2024-04-20T08:30:00Z',
                  _2: {
                    transferConfig: {
                      createFee: {
                        fee: '110.0300000000',
                      },
                      holdingFee: {
                        rate: '0.0000048225',
                      },
                      transferFee: {
                        initialRate: '0.0100000000',
                        steps: [
                          {
                            _1: '100.0000000000',
                            _2: '0.0010000000',
                          },
                          {
                            _1: '1000.0000000000',
                            _2: '0.0001000000',
                          },
                          {
                            _1: '1000000.0000000000',
                            _2: '0.0000100000',
                          },
                        ],
                      },
                      lockHolderFee: {
                        fee: '0.0050000000',
                      },
                      extraFeaturedAppRewardAmount: '1.0000000000',
                      maxNumInputs: '100',
                      maxNumOutputs: '100',
                      maxNumLockHolders: '50',
                    },
                    issuanceCurve: {
                      initialValue: {
                        amuletToIssuePerYear: '40000000000.0000000000',
                        validatorRewardPercentage: '0.0500000000',
                        appRewardPercentage: '0.1500000000',
                        validatorRewardCap: '0.2000000000',
                        featuredAppRewardCap: '100.0000000000',
                        unfeaturedAppRewardCap: '0.6000000000',
                        optValidatorFaucetCap: '2.8500000000',
                      },
                      futureValues: [
                        {
                          _1: {
                            microseconds: '15768000000000',
                          },
                          _2: {
                            amuletToIssuePerYear: '20000000000.0000000000',
                            validatorRewardPercentage: '0.1200000000',
                            appRewardPercentage: '0.4000000000',
                            validatorRewardCap: '0.2000000000',
                            featuredAppRewardCap: '100.0000000000',
                            unfeaturedAppRewardCap: '0.6000000000',
                            optValidatorFaucetCap: '2.8500000000',
                          },
                        },
                        {
                          _1: {
                            microseconds: '47304000000000',
                          },
                          _2: {
                            amuletToIssuePerYear: '10000000000.0000000000',
                            validatorRewardPercentage: '0.1800000000',
                            appRewardPercentage: '0.6200000000',
                            validatorRewardCap: '0.2000000000',
                            featuredAppRewardCap: '100.0000000000',
                            unfeaturedAppRewardCap: '0.6000000000',
                            optValidatorFaucetCap: '2.8500000000',
                          },
                        },
                        {
                          _1: {
                            microseconds: '157680000000000',
                          },
                          _2: {
                            amuletToIssuePerYear: '5000000000.0000000000',
                            validatorRewardPercentage: '0.2100000000',
                            appRewardPercentage: '0.6900000000',
                            validatorRewardCap: '0.2000000000',
                            featuredAppRewardCap: '100.0000000000',
                            unfeaturedAppRewardCap: '0.6000000000',
                            optValidatorFaucetCap: '2.8500000000',
                          },
                        },
                        {
                          _1: {
                            microseconds: '315360000000000',
                          },
                          _2: {
                            amuletToIssuePerYear: '2500000000.0000000000',
                            validatorRewardPercentage: '0.2000000000',
                            appRewardPercentage: '0.7500000000',
                            validatorRewardCap: '0.2000000000',
                            featuredAppRewardCap: '100.0000000000',
                            unfeaturedAppRewardCap: '0.6000000000',
                            optValidatorFaucetCap: '2.8500000000',
                          },
                        },
                      ],
                    },
                    decentralizedSynchronizer: {
                      requiredSynchronizers: {
                        map: [
                          [
                            'global-domain::122013fe9b84dfc756163484f071da40f5605c2ab9d93eb647052c15e360acce1347',
                            {},
                          ],
                        ],
                      },
                      activeSynchronizer:
                        'global-domain::122013fe9b84dfc756163484f071da40f5605c2ab9d93eb647052c15e360acce1347',
                      fees: {
                        baseRateTrafficLimits: {
                          burstAmount: '2000000',
                          burstWindow: {
                            microseconds: '600000000',
                          },
                        },
                        extraTrafficPrice: '1.0000000000',
                        readVsWriteScalingFactor: '4',
                        minTopupAmount: '10000000',
                      },
                    },
                    tickDuration: {
                      microseconds: '150000000',
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
                },
              },
            },
          },
        },
        reason: {
          url: '',
          body: 'ads',
        },
        voteBefore: '2024-04-19T08:25:11.839403Z',
        votes: [
          [
            'Digital-Asset-2',
            {
              sv: 'digital-asset-2::122045496d88b854f49ce75e9032e73ee8157d14f7d05ecbdc8895b566f409e68fe8',
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
  ],
};

// Sanity check / guard against template changes

const result = jtv.Result.andThen(
  () => AmuletRules.decoder.run(dsoInfo.amulet_rules.contract.payload),
  DsoRules.decoder.run(dsoInfo.dso_rules.contract.payload)
);
if (!result.ok) {
  throw new Error(`Invalid DsoInfo mock: ${JSON.stringify(result.error)}`);
}

export const svPartyId = dsoInfo.sv_party_id;
