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
    transferPreapprovalFee: null,
  };
}

// eslint-disable-next-line @typescript-eslint/explicit-module-boundary-types
export function getAmuletRulesAction(action: string, effectiveAt: string, createFee: string) {
  return {
    tag: 'ARC_AmuletRules',
    value: {
      amuletRulesAction: {
        tag: action,
        value: {
          newScheduleItem: {
            _1: effectiveAt,
            _2: getAmuletConfig(createFee),
          },
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
    '}</pre></div></li><li data-key="issuanceCurve" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">issuanceCurve</div><div class="jsondiffpatch-value"><pre>{\n' +
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
    '  "amulet": "0.1.5",\n' +
    '  "amuletNameService": "0.1.5",\n' +
    '  "dsoGovernance": "0.1.8",\n' +
    '  "validatorLifecycle": "0.1.1",\n' +
    '  "wallet": "0.1.5",\n' +
    '  "walletPayments": "0.1.5"\n' +
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
    '<li data-key="transferPreapprovalFee" class="jsondiffpatch-unchanged"><div class="jsondiffpatch-property-name">transferPreapprovalFee</div><div class="jsondiffpatch-value"><pre>null</pre></div></li></div>\n'.trim()
  );
}
