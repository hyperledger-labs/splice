import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';

import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import {
  ActionRequiringConfirmation,
  Vote,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import * as damlTypes from '@daml/types';
import { ContractId } from '@daml/types';

export function mkVoteRequest(action: ActionRequiringConfirmation): Contract<VoteRequest> {
  return {
    templateId:
      '2790a114f83d5f290261fae1e7e46fba75a861a3dd603c6b4ef6b67b49053948:Splice.DsoRules:VoteRequest',
    contractId: ContractId(VoteRequest).decoder.runWithException(
      '10f1a2cbcd5a2dc9ad2fb9d17fec183d75de19ca91f623cbd2eaaf634e8d7cb4b5ca101220b5c5c20442f608e151ca702e0c4f51341a338c5979c0547dfcc80f911061ca91'
    ) as ContractId<VoteRequest>,
    payload: {
      dso: 'DSO::1220ebe7643fe0617f6f8e1d147137a3b174b350adf0ac2280f967c9abb712c81afb',
      votes: damlTypes.emptyMap<string, Vote>().set('Digital-Asset-2', {
        sv: 'digital-asset-2::122063072c8e53ca2690deeff0be9002ac252f9927caebec8e2f64233b95db66da31',
        accept: true,
        reason: {
          url: '',
          body: 'I accept, as I requested the vote.',
        },
        optCastAt: null,
      }),
      voteBefore: '2098-09-11T10:27:52.300591Z',
      requester: 'Digital-Asset-2',
      reason: {
        url: '',
        body: 'df',
      },
      trackingCid: null,
      action: action,
      targetEffectiveAt: null,
    },
    createdEventBlob: '',
    createdAt: '2014-09-11T10:28:09.304591Z',
  };
}

export function getAmuletRulesAddFutureScheduleAction(
  effectiveAt: string,
  createFee: string
): ActionRequiringConfirmation {
  return {
    tag: 'ARC_AmuletRules',
    value: {
      amuletRulesAction: {
        tag: 'CRARC_AddFutureAmuletConfigSchedule',
        value: {
          newScheduleItem: {
            _1: effectiveAt,
            _2: getAmuletRulesConfig(createFee),
          },
        },
      },
    },
  };
}

export function getAmuletRulesSetConfigAction(createFee: string): ActionRequiringConfirmation {
  return {
    tag: 'ARC_AmuletRules',
    value: {
      amuletRulesAction: {
        tag: 'CRARC_SetConfig',
        value: {
          newConfig: getAmuletRulesConfig(createFee),
          baseConfig: getAmuletRulesConfig('0'),
        },
      },
    },
  };
}

export function getAmuletRulesConfig(
  createFee: string,
  baseRateTrafficLimitsBurstWindow: string = '1200000000'
): AmuletConfig<USD> {
  return {
    packageConfig: {
      amuletNameService: '0.1.8',
      walletPayments: '0.1.8',
      dsoGovernance: '0.1.11',
      validatorLifecycle: '0.1.2',
      amulet: '0.1.8',
      wallet: '0.1.8',
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
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        ] as any,
      },
      activeSynchronizer:
        'global-domain::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81',
      fees: {
        baseRateTrafficLimits: {
          burstAmount: '400000',
          burstWindow: {
            microseconds: baseRateTrafficLimitsBurstWindow,
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
    transferPreapprovalFee: null,
    featuredAppActivityMarkerAmount: null,
  };
}

export function getAmuletSetConfigAction(
  createFee: { new: string; base: string },
  baseRateTrafficLimitsBurstWindow: { new: string; base: string }
): ActionRequiringConfirmation {
  return {
    tag: 'ARC_AmuletRules',
    value: {
      amuletRulesAction: {
        tag: 'CRARC_SetConfig',
        value: {
          newConfig: getAmuletRulesConfig(createFee.new, baseRateTrafficLimitsBurstWindow.new),
          baseConfig: getAmuletRulesConfig(createFee.base, baseRateTrafficLimitsBurstWindow.base),
        },
      },
    },
  };
}

export function getExpectedAmuletRulesConfigDiffsHTML(
  originalCreateFee: string,
  replacementCreateFee: string
): string {
  return (
    '<div class="jsondiffpatch-delta jsondiffpatch-node jsondiffpatch-child-node-type-object"><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"><li data-key="decentralizedSynchronizer" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">decentralizedSynchronizer</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "requiredSynchronizers": {\n' +
    '    "map": [\n' +
    '      [\n' +
    '        "global-domain::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81",\n' +
    '        {}\n' +
    '      ]\n' +
    '    ]\n' +
    '  },\n' +
    '  "activeSynchronizer": "global-domain::12200c1f141acd0b2e48defae40aa2eb3daae48e4c16b7e1fa5d9211d352cc150c81",\n' +
    '  "fees": {\n' +
    '    "baseRateTrafficLimits": {\n' +
    '      "burstAmount": "400000",\n' +
    '      "burstWindow": {\n' +
    '        "microseconds": "1200000000"\n' +
    '      }\n' +
    '    },\n' +
    '    "extraTrafficPrice": "16.67",\n' +
    '    "readVsWriteScalingFactor": "4",\n' +
    '    "minTopupAmount": "200000"\n' +
    '  }\n' +
    '}</pre></div></li>' +
    '<li data-key="featuredAppActivityMarkerAmount" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">featuredAppActivityMarkerAmount</div><div class="jsondiffpatch-value"><pre>null</pre></div></li>' +
    '<li data-key="issuanceCurve" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">issuanceCurve</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "initialValue": {\n' +
    '    "amuletToIssuePerYear": "40000000000.0",\n' +
    '    "validatorRewardPercentage": "0.05",\n' +
    '    "appRewardPercentage": "0.15",\n' +
    '    "validatorRewardCap": "0.2",\n' +
    '    "featuredAppRewardCap": "100.0",\n' +
    '    "unfeaturedAppRewardCap": "0.6",\n' +
    '    "optValidatorFaucetCap": "2.85"\n' +
    '  },\n' +
    '  "futureValues": [\n' +
    '    {\n' +
    '      "_1": {\n' +
    '        "microseconds": "15768000000000"\n' +
    '      },\n' +
    '      "_2": {\n' +
    '        "amuletToIssuePerYear": "20000000000.0",\n' +
    '        "validatorRewardPercentage": "0.12",\n' +
    '        "appRewardPercentage": "0.4",\n' +
    '        "validatorRewardCap": "0.2",\n' +
    '        "featuredAppRewardCap": "100.0",\n' +
    '        "unfeaturedAppRewardCap": "0.6",\n' +
    '        "optValidatorFaucetCap": "2.85"\n' +
    '      }\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": {\n' +
    '        "microseconds": "47304000000000"\n' +
    '      },\n' +
    '      "_2": {\n' +
    '        "amuletToIssuePerYear": "10000000000.0",\n' +
    '        "validatorRewardPercentage": "0.18",\n' +
    '        "appRewardPercentage": "0.62",\n' +
    '        "validatorRewardCap": "0.2",\n' +
    '        "featuredAppRewardCap": "100.0",\n' +
    '        "unfeaturedAppRewardCap": "0.6",\n' +
    '        "optValidatorFaucetCap": "2.85"\n' +
    '      }\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": {\n' +
    '        "microseconds": "157680000000000"\n' +
    '      },\n' +
    '      "_2": {\n' +
    '        "amuletToIssuePerYear": "5000000000.0",\n' +
    '        "validatorRewardPercentage": "0.21",\n' +
    '        "appRewardPercentage": "0.69",\n' +
    '        "validatorRewardCap": "0.2",\n' +
    '        "featuredAppRewardCap": "100.0",\n' +
    '        "unfeaturedAppRewardCap": "0.6",\n' +
    '        "optValidatorFaucetCap": "2.85"\n' +
    '      }\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": {\n' +
    '        "microseconds": "315360000000000"\n' +
    '      },\n' +
    '      "_2": {\n' +
    '        "amuletToIssuePerYear": "2500000000.0",\n' +
    '        "validatorRewardPercentage": "0.2",\n' +
    '        "appRewardPercentage": "0.75",\n' +
    '        "validatorRewardCap": "0.2",\n' +
    '        "featuredAppRewardCap": "100.0",\n' +
    '        "unfeaturedAppRewardCap": "0.6",\n' +
    '        "optValidatorFaucetCap": "2.85"\n' +
    '      }\n' +
    '    }\n' +
    '  ]\n' +
    '}</pre></div></li><li data-key="packageConfig" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">packageConfig</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "amulet": "0.1.8",\n' +
    '  "amuletNameService": "0.1.8",\n' +
    '  "dsoGovernance": "0.1.11",\n' +
    '  "validatorLifecycle": "0.1.2",\n' +
    '  "wallet": "0.1.8",\n' +
    '  "walletPayments": "0.1.8"\n' +
    '}</pre></div></li><li data-key="tickDuration" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">tickDuration</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "microseconds": "600000000"\n' +
    `}</pre></div></li><li data-key="transferConfig" class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div class="jsondiffpatch-property-name">transferConfig</div><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"></ul></li><li data-key="createFee" class="jsondiffpatch-node jsondiffpatch-child-node-type-object"><div class="jsondiffpatch-property-name">createFee</div><ul class="jsondiffpatch-node jsondiffpatch-node-type-object"></ul></li><li data-key="fee" class="jsondiffpatch-modified"><div class="jsondiffpatch-property-name">fee</div><div class="jsondiffpatch-value jsondiffpatch-left-value"><pre>"${originalCreateFee}"</pre></div><div class="jsondiffpatch-value jsondiffpatch-right-value"><pre>"${replacementCreateFee}"</pre></div></li></ul><li data-key="extraFeaturedAppRewardAmount" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">extraFeaturedAppRewardAmount</div><div class="jsondiffpatch-value"><pre>"1.0"</pre></div></li><li data-key="holdingFee" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">holdingFee</div><div class="jsondiffpatch-value"><pre>{\n` +
    '  "rate": "0.0000190259"\n' +
    '}</pre></div></li><li data-key="lockHolderFee" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">lockHolderFee</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "fee": "0.005"\n' +
    '}</pre></div></li><li data-key="maxNumInputs" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">maxNumInputs</div><div class="jsondiffpatch-value"><pre>"100"</pre></div></li><li data-key="maxNumLockHolders" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">maxNumLockHolders</div><div class="jsondiffpatch-value"><pre>"50"</pre></div></li><li data-key="maxNumOutputs" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">maxNumOutputs</div><div class="jsondiffpatch-value"><pre>"100"</pre></div></li><li data-key="transferFee" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">transferFee</div><div class="jsondiffpatch-value"><pre>{\n' +
    '  "initialRate": "0.01",\n' +
    '  "steps": [\n' +
    '    {\n' +
    '      "_1": "100.0",\n' +
    '      "_2": "0.001"\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": "1000.0",\n' +
    '      "_2": "0.0001"\n' +
    '    },\n' +
    '    {\n' +
    '      "_1": "1000000.0",\n' +
    '      "_2": "0.00001"\n' +
    '    }\n' +
    '  ]\n' +
    '}</pre></div></li>' +
    '<li data-key="transferPreapprovalFee" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">transferPreapprovalFee</div><div class="jsondiffpatch-value"><pre>null</pre></div></li>' +
    '</div>\n'.trim()
  );
}
