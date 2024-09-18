// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ContractId, emptyMap } from '@daml/types';

import { AmuletRules } from '../../../daml.js/splice-amulet-0.1.5/lib/Splice/AmuletRules';
import {
  SynchronizerConfig,
  SynchronizerNodeConfig,
} from '../../../daml.js/splice-dso-governance-0.1.8/lib/Splice/DSO/DecentralizedSynchronizer';
import { SvNodeState } from '../../../daml.js/splice-dso-governance-0.1.8/lib/Splice/DSO/SvState';
import {
  DsoRules,
  DsoRules_CloseVoteRequestResult,
  SvInfo,
  TrafficState,
  Vote,
  VoteRequest,
} from '../../../daml.js/splice-dso-governance-0.1.8/lib/Splice/DsoRules';
import { Contract } from '../../../utils';
import { DsoInfo } from '../../components';
import { SvVote } from '../../models';

export const dsoInfo: DsoInfo = {
  svUser: 'sv1',
  svPartyId:
    'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
  dsoPartyId: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
  votingThreshold: BigInt(1),
  amuletRules: {
    templateId:
      'b4867a47abbfa2d15482f22c9c50516f0af14036287f299342f5d336391e4997:Splice.AmuletRules:AmuletRules',
    contractId:
      '00e416d204d672331182b8913f7ef9d0296b2268d0eb6eeadedae4e5cef3e3de13ca10122053e7b8d6155ab56f6120fd7f43827abc2f3d24e4df44f0773bf09b8adea2233a' as ContractId<AmuletRules>,
    payload: {
      dso: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
      configSchedule: {
        initialValue: {
          packageConfig: {
            amuletNameService: '0.1.5',
            walletPayments: '0.1.5',
            dsoGovernance: '0.1.8',
            validatorLifecycle: '0.1.1',
            amulet: '0.1.5',
            wallet: '0.1.5',
          },
          tickDuration: { microseconds: '600000000' },
          decentralizedSynchronizer: {
            requiredSynchronizers: {
              /* eslint-disable */
              map: emptyMap<string, {}>().set(
                'global-domain::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
                {}
              ),
              /* eslint-enable */
            },
            activeSynchronizer:
              'global-domain::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
            fees: {
              baseRateTrafficLimits: {
                burstAmount: '400000',
                burstWindow: { microseconds: '1200000000' },
              },
              extraTrafficPrice: '16.67',
              readVsWriteScalingFactor: '4',
              minTopupAmount: '200000',
            },
          },
          transferConfig: {
            holdingFee: { rate: '0.0000190259' },
            extraFeaturedAppRewardAmount: '1.0',
            maxNumInputs: '100',
            lockHolderFee: { fee: '0.005' },
            createFee: { fee: '0.03' },
            maxNumLockHolders: '50',
            transferFee: {
              initialRate: '0.01',
              steps: [
                { _1: '100.0', _2: '0.001' },
                { _1: '1000.0', _2: '0.0001' },
                { _1: '1000000.0', _2: '0.00001' },
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
                _1: { microseconds: '15768000000000' },
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
                _1: { microseconds: '47304000000000' },
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
                _1: { microseconds: '157680000000000' },
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
                _1: { microseconds: '315360000000000' },
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
        },
        futureValues: [],
      },
      isDevNet: true,
    },
    createdEventBlob:
      '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',
    createdAt: '2024-09-05T07:46:59.850622Z',
  },
  dsoRules: {
    templateId:
      '1790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DsoRules:DsoRules',
    contractId:
      '004bd91acc029467059589a047ca97c1192fca684a09ec3bca5592a63d4c27dd5fca101220728231789c3768943bf6c46b28efc1d326fed789e6f6058b56dae1f527537a41' as ContractId<DsoRules>,
    payload: {
      dso: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
      svs: emptyMap<string, SvInfo>().set(
        'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
        {
          name: 'Digital-Asset-2',
          joinedAsOfRound: { number: '0' },
          svRewardWeight: '200000',
          participantId:
            'PAR::sv1::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
        }
      ),
      epoch: '0',
      offboardedSvs: emptyMap(),
      initialTrafficState: emptyMap<string, TrafficState>()
        .set('MED::sv1::12204bed0e9dd1c4ad7c1c3732c010bf2c9e7da75561979a8dcd9a7a17110d952511', {
          consumedTraffic: '0',
        })
        .set('PAR::sv1::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41', {
          consumedTraffic: '0',
        }),
      dsoDelegate:
        'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      config: {
        numMemberTrafficContractsThreshold: '5',
        dsoDelegateInactiveTimeout: { microseconds: '70000000' },
        svOnboardingRequestTimeout: { microseconds: '3600000000' },
        nextScheduledSynchronizerUpgrade: null,
        actionConfirmationTimeout: { microseconds: '3600000000' },
        maxTextLength: '1024',
        voteRequestTimeout: { microseconds: '604800000000' },
        decentralizedSynchronizer: {
          synchronizers: emptyMap<string, SynchronizerConfig>().set(
            'global-domain::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
            {
              state: 'DS_Operational',
              cometBftGenesisJson:
                'TODO(#4900): share CometBFT genesis.json of sv1 via DsoRules config.',
              acsCommitmentReconciliationInterval: '1800',
            }
          ),
          lastSynchronizerId:
            'global-domain::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
          activeSynchronizerId:
            'global-domain::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
        },
        numUnclaimedRewardsThreshold: '10',
        svOnboardingConfirmedTimeout: { microseconds: '3600000000' },
        synchronizerNodeConfigLimits: {
          cometBft: {
            maxNumSequencingKeys: '2',
            maxNodeIdLength: '50',
            maxNumCometBftNodes: '2',
            maxPubKeyLength: '256',
            maxNumGovernanceKeys: '2',
          },
        },
      },
      isDevNet: true,
    },
    createdEventBlob:
      'CgMyLjES1QsKRQBL2RrMApRnBZWJoEfKl8EZL8poSgnsO8pVkqY9TCfdX8oQEiBygjF4nDdolDv2xGso78HTJv7Xieb2BYtW2uH1J1N6QRIVc3BsaWNlLWRzby1nb3Zlcm5hbmNlGl4KQDE3OTBhMTE0ZjgzZDVmMjkwMjYxZmFlMWU3ZTQ2ZmJhNzVhODYxYTNkZDYwM2M2YjRlZjZiNjdiNDkwNTM5NDgSBlNwbGljZRIIRHNvUnVsZXMaCERzb1J1bGVzIpQJapEJCk0KSzpJRFNPOjoxMjIwNThmZDYxYTU2NGYwMzgyM2JjMWUyN2YxNmRlNTE5Y2FiY2QzNjgxZmMzMWFiNTIzZjRlZjY5OGRjNmI2YzNhYgoECgIYAArjAQrgAWLdAQraAQpXOlVkaWdpdGFsLWFzc2V0LTI6OjEyMjBhMTUwNGQ4NzQ1MWNlNmE0YTEzNTVkNzFjNGYyNjY3N2Q2M2RiNWU2Mjk4ZDA0YjBkMTg2NWZkOTcxNDMyYTQxEn9qfQoTChFCD0RpZ2l0YWwtQXNzZXQtMgoKCghqBgoECgIYAAoGCgQYgLUYClIKUEJOUEFSOjpzdjE6OjEyMjBhMTUwNGQ4NzQ1MWNlNmE0YTEzNTVkNzFjNGYyNjY3N2Q2M2RiNWU2Mjk4ZDA0YjBkMTg2NWZkOTcxNDMyYTQxCgQKAmIAClkKVzpVZGlnaXRhbC1hc3NldC0yOjoxMjIwYTE1MDRkODc0NTFjZTZhNGExMzU1ZDcxYzRmMjY2NzdkNjNkYjVlNjI5OGQwNGIwZDE4NjVmZDk3MTQzMmE0MQqnBAqkBGqhBAoECgIYFAoECgIYCgoOCgxqCgoICgYYgJCd6RoKDgoMagoKCAoGGICQnekaCg4KDGoKCggKBhiAkJ3pGgoPCg1qCwoJCgcYgICdjpojCg0KC2oJCgcKBRiA9uBCCikKJ2olCiMKIWofCgQKAhgECgQKAhgECgQKAhgECgQKAhhkCgUKAxiABAoFCgMYgBAKigMKhwNqhAMKzwEKzAFiyQEKxgEKVUJTZ2xvYmFsLWRvbWFpbjo6MTIyMDU4ZmQ2MWE1NjRmMDM4MjNiYzFlMjdmMTZkZTUxOWNhYmNkMzY4MWZjMzFhYjUyM2Y0ZWY2OThkYzZiNmMzYWISbWprChQKEnoQCg5EU19PcGVyYXRpb25hbApICkZCRFRPRE8oIzQ5MDApOiBzaGFyZSBDb21ldEJGVCBnZW5lc2lzLmpzb24gb2Ygc3YxIHZpYSBEc29SdWxlcyBjb25maWcuCgkKB1IFCgMYkBwKVwpVQlNnbG9iYWwtZG9tYWluOjoxMjIwNThmZDYxYTU2NGYwMzgyM2JjMWUyN2YxNmRlNTE5Y2FiY2QzNjgxZmMzMWFiNTIzZjRlZjY5OGRjNmI2YzNhYgpXClVCU2dsb2JhbC1kb21haW46OjEyMjA1OGZkNjFhNTY0ZjAzODIzYmMxZTI3ZjE2ZGU1MTljYWJjZDM2ODFmYzMxYWI1MjNmNGVmNjk4ZGM2YjZjM2FiCgQKAlIACsIBCr8BYrwBClwKUEJOTUVEOjpzdjE6OjEyMjA0YmVkMGU5ZGQxYzRhZDdjMWMzNzMyYzAxMGJmMmM5ZTdkYTc1NTYxOTc5YThkY2Q5YTdhMTcxMTBkOTUyNTExEghqBgoECgIYAApcClBCTlBBUjo6c3YxOjoxMjIwYTE1MDRkODc0NTFjZTZhNGExMzU1ZDcxYzRmMjY2NzdkNjNkYjVlNjI5OGQwNGIwZDE4NjVmZDk3MTQzMmE0MRIIagYKBAoCGAAKBAoCEAEqSURTTzo6MTIyMDU4ZmQ2MWE1NjRmMDM4MjNiYzFlMjdmMTZkZTUxOWNhYmNkMzY4MWZjMzFhYjUyM2Y0ZWY2OThkYzZiNmMzYWI5V8i5j1ohBgBCKgomCiQIARIgTW71Xfmw1o8i+OvxuacgrMZRpKV/5AHxr8CcQthjnmkQHg==',
    createdAt: '2024-09-05T07:51:42.354519Z',
  },
  nodeStates: [
    {
      templateId:
        '1790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DSO.SvState:SvNodeState',
      contractId:
        '004d6ef053938ee16cbb24fa8439758f6d4fcbd8d5c21240c7fe5bacb5d7b6e3b4ca101220f43751465f901483671b312528079d97df78b55f11e8bdaf4960f6648f6db304' as ContractId<SvNodeState>,
      payload: {
        dso: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
        sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
        svName: 'Digital-Asset-2',
        state: {
          synchronizerNodes: emptyMap<string, SynchronizerNodeConfig>().set(
            'global-domain::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
            {
              legacySequencerConfig: null,
              scan: { publicUrl: 'http://localhost:5012' },
              mediator: {
                mediatorId:
                  'MED::sv1::12204bed0e9dd1c4ad7c1c3732c010bf2c9e7da75561979a8dcd9a7a17110d952511',
              },
              cometBft: { nodes: emptyMap(), governanceKeys: [], sequencingKeys: [] },
              sequencer: {
                migrationId: '0',
                sequencerId:
                  'SEQ::sv1::12205192f07b7a2ad63ad8578e50986acd64c2cb130d021e76cdc468c0bb8529adeb',
                url: 'http://localhost:5108',
                availableAfter: '2024-09-05T07:46:59.268227Z',
              },
            }
          ),
        },
      },
      createdEventBlob:
        'CgMyLjESsgcKRQBNbvBTk47hbLsk+oQ5dY9tT8vY1cISQMf+W6y117bjtMoQEiD0N1FGX5AUg2cbMSUoB52X33i1XxHova9JYPZkj22zBBIVc3BsaWNlLWRzby1nb3Zlcm5hbmNlGmUKQDE3OTBhMTE0ZjgzZDVmMjkwMjYxZmFlMWU3ZTQ2ZmJhNzVhODYxYTNkZDYwM2M2YjRlZjZiNjdiNDkwNTM5NDgSBlNwbGljZRIDRFNPEgdTdlN0YXRlGgtTdk5vZGVTdGF0ZSLqBGrnBApNCks6SURTTzo6MTIyMDU4ZmQ2MWE1NjRmMDM4MjNiYzFlMjdmMTZkZTUxOWNhYmNkMzY4MWZjMzFhYjUyM2Y0ZWY2OThkYzZiNmMzYWIKWQpXOlVkaWdpdGFsLWFzc2V0LTI6OjEyMjBhMTUwNGQ4NzQ1MWNlNmE0YTEzNTVkNzFjNGYyNjY3N2Q2M2RiNWU2Mjk4ZDA0YjBkMTg2NWZkOTcxNDMyYTQxChMKEUIPRGlnaXRhbC1Bc3NldC0yCqUDCqIDap8DCpwDCpkDYpYDCpMDClVCU2dsb2JhbC1kb21haW46OjEyMjA1OGZkNjFhNTY0ZjAzODIzYmMxZTI3ZjE2ZGU1MTljYWJjZDM2ODFmYzMxYWI1MjNmNGVmNjk4ZGM2YjZjM2FiErkCarYCChYKFGoSCgQKAmIACgQKAloACgQKAloACpIBCo8BUowBCokBaoYBCgQKAhgAClIKUEJOU0VROjpzdjE6OjEyMjA1MTkyZjA3YjdhMmFkNjNhZDg1NzhlNTA5ODZhY2Q2NGMyY2IxMzBkMDIxZTc2Y2RjNDY4YzBiYjg1MjlhZGViChkKF0IVaHR0cDovL2xvY2FsaG9zdDo1MTA4Cg8KDVILCgkpgzraflohBgAKXApaUlgKVmpUClIKUEJOTUVEOjpzdjE6OjEyMjA0YmVkMGU5ZGQxYzRhZDdjMWMzNzMyYzAxMGJmMmM5ZTdkYTc1NTYxOTc5YThkY2Q5YTdhMTcxMTBkOTUyNTExCiMKIVIfCh1qGwoZChdCFWh0dHA6Ly9sb2NhbGhvc3Q6NTAxMgoECgJSACpJRFNPOjoxMjIwNThmZDYxYTU2NGYwMzgyM2JjMWUyN2YxNmRlNTE5Y2FiY2QzNjgxZmMzMWFiNTIzZjRlZjY5OGRjNmI2YzNhYjl+HeN+WiEGAEIqCiYKJAgBEiBiucwkodah/ulIzXG3O5FLjMwT8y81o0zRvyWN98oT0RAe',
      createdAt: '2024-09-05T07:46:59.850622Z',
    },
  ],
};

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

export const plannedVoteResult: DsoRules_CloseVoteRequestResult = {
  request: {
    dso: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
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
                    fee: '0.0300000000',
                  },
                  holdingFee: {
                    rate: '0.0000190259',
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
                    fee: '0.0060000000',
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
                    /* eslint-disable */
                    map: emptyMap<string, {}>().set(
                      'global-domain::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
                      {}
                    ),
                    /* eslint-enable */
                  },
                  activeSynchronizer:
                    'global-domain::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
                  fees: {
                    baseRateTrafficLimits: {
                      burstAmount: '400000',
                      burstWindow: {
                        microseconds: '1200000000',
                      },
                    },
                    extraTrafficPrice: '16.6700000000',
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
    voteBefore: '2024-09-05T08:40:55.977789Z',
    votes: emptyMap<string, Vote>().set('Digital-Asset-2', {
      sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      reason: {
        url: '',
        body: 'yes',
      },
      accept: true,
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
    dso: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
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
    voteBefore: '2024-09-13T07:51:14.091940Z',
    votes: emptyMap<string, Vote>().set('Digital-Asset-2', {
      sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      reason: {
        url: '',
        body: 'yes',
      },
      accept: true,
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
    dso: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
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
    votes: emptyMap<string, Vote>().set('Digital-Asset-2', {
      sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      accept: false,
      reason: {
        url: '',
        body: 'I reject, as I reconsidered.',
      },
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
    dso: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
    votes: emptyMap<string, Vote>().set('Digital-Asset-2', {
      sv: 'digital-asset-2::1220a1504d87451ce6a4a1355d71c4f26677d63db5e6298d04b0d1865fd971432a41',
      reason: {
        url: '',
        body: 'yes',
      },
      accept: true,
    }),
    voteBefore: '2024-09-12T08:13:16.584772Z',
    requester: 'Digital-Asset-2',
    reason: {
      url: '',
      body: 'Test 3',
    },
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

export const unvotedRequest = {
  templateId:
    '1790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DsoRules:VoteRequest',
  contractId:
    '11bb0a31fc2054db0365c7063db93cc43cfc9fd79f70bc78daff656a9bd6f9370fca101220ad980d3af8d54ed5ca8927ee0a2eaa65a4033e63c9a646fca0f05446676748cb' as ContractId<VoteRequest>,
  payload: {
    dso: 'DSO::122058fd61a564f03823bc1e27f16de519cabcd3681fc31ab523f4ef698dc6b6c3ab',
    votes: emptyMap<string, Vote>(),
    voteBefore: '2024-09-12T08:13:16.584772Z',
    requester: 'Digital-Asset-2',
    reason: {
      url: '',
      body: 'Test 3',
    },
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
