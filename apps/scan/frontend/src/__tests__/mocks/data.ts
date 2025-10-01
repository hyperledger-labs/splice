// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules/module';

import damlTypes from '@daml/types';
import { GetAmuletRulesResponse } from '@lfdecentralizedtrust/scan-openapi';

export function getAmuletRulesResponse(zeroTransferFees: boolean): GetAmuletRulesResponse {
  return {
    amulet_rules_update: {
      contract: {
        template_id:
          'f4693252b3cab434649f66f5fd309ae98ca01512b10b482086aa8ff529ca83e3:Splice.Amulet:AmuletRules',
        contract_id:
          '00ed7531fa0fb6a06f0d0f1ea8a31867704da8a6c341e7262894c5d0e15312aca6ca0212200412a9e6c1b9bff1449205e02c88596bad60b8eb8d14bee48f26509f6531d4db',
        payload: amuletRules(zeroTransferFees),
        created_event_blob: '',
        created_at: '2023-10-06T08:12:16.481271Z',
      },
      domain_id:
        'global-domain::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
    },
  };
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function amuletRules(zeroTransferFees: boolean): any {
  const transferFeeSteps = zeroTransferFees
    ? []
    : [
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
      ];
  return AmuletRules.encode({
    dso: 'DSO::1220af85fa0c58e7f551de289be22793993ce7672cb0751afa2f2de397ce4a695677',
    configSchedule: {
      initialValue: {
        transferConfig: {
          holdingFee: {
            rate: '0.0000048225',
          },
          maxNumInputs: '100',
          lockHolderFee: {
            fee: zeroTransferFees ? '0.0' : '0.005',
          },
          createFee: {
            fee: zeroTransferFees ? '0.0' : '0.031',
          },
          extraFeaturedAppRewardAmount: '1.0',
          maxNumLockHolders: '50',
          transferFee: {
            initialRate: zeroTransferFees ? '0.0' : '0.01',
            steps: transferFeeSteps,
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
        transferPreapprovalFee: null,
        featuredAppActivityMarkerAmount: null,
      },
      futureValues: [],
    },
    isDevNet: true,
  });
}
