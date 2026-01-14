// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  DeploySvRunbook,
  isDevNet,
  isMainNet,
  SvCometBftKeys,
  svCometBftKeysFromSecret,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { SweepConfig } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { spliceEnvConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig';

import { StaticSvConfig } from './config';
import { dsoSize, skipExtraSvs } from './dsoConfig';
import { configForSv, configuredExtraSvs } from './singleSvConfig';

const sv1ScanBigQuery = spliceEnvConfig.envFlag('SV1_SCAN_BIGQUERY', false);

const svCometBftSecrets: pulumi.Output<SvCometBftKeys>[] = isMainNet
  ? [svCometBftKeysFromSecret('sv1-cometbft-keys')]
  : [
      svCometBftKeysFromSecret('sv1-cometbft-keys'),
      svCometBftKeysFromSecret('sv2-cometbft-keys'),
      svCometBftKeysFromSecret('sv3-cometbft-keys'),
      svCometBftKeysFromSecret('sv4-cometbft-keys'),
      svCometBftKeysFromSecret('sv5-cometbft-keys'),
      svCometBftKeysFromSecret('sv6-cometbft-keys'),
      svCometBftKeysFromSecret('sv7-cometbft-keys'),
      svCometBftKeysFromSecret('sv8-cometbft-keys'),
      svCometBftKeysFromSecret('sv9-cometbft-keys'),
      svCometBftKeysFromSecret('sv10-cometbft-keys'),
      svCometBftKeysFromSecret('sv11-cometbft-keys'),
      svCometBftKeysFromSecret('sv12-cometbft-keys'),
      svCometBftKeysFromSecret('sv13-cometbft-keys'),
      svCometBftKeysFromSecret('sv14-cometbft-keys'),
      svCometBftKeysFromSecret('sv15-cometbft-keys'),
      svCometBftKeysFromSecret('sv16-cometbft-keys'),
    ];

// TODO(#1892): we can think about moving all of the values here to config.yaml ; it's a bit risky/expensive to test though
const fromSingleSvConfig = (nodeName: string, cometBftNodeIndex: number): StaticSvConfig => {
  const config = configForSv(nodeName);

  const svCometBftSecretName = config.cometbft?.keysGcpSecret
    ? config.cometbft.keysGcpSecret
    : `${nodeName.replaceAll('-', '')}-cometbft-keys`;
  const svCometBftSecrets = svCometBftKeysFromSecret(svCometBftSecretName);

  return {
    nodeName,
    ingressName: config.subdomain!,
    onboardingName: config.publicName!,
    auth0ValidatorAppName: config.validatorApp?.auth0?.name
      ? config.validatorApp.auth0.name
      : `${nodeName}_validator`,
    auth0SvAppName: config.svApp?.auth0?.name ? config.svApp.auth0.name : nodeName,
    validatorWalletUser: config.validatorApp?.walletUser,
    cometBft: {
      nodeIndex: cometBftNodeIndex,
      id: config.cometbft!.nodeId!,
      privateKey: svCometBftSecrets.nodePrivateKey,
      validator: {
        keyAddress: config.cometbft!.validatorKeyAddress!,
        privateKey: svCometBftSecrets.validatorPrivateKey,
        publicKey: svCometBftSecrets.validatorPublicKey,
      },
    },
    svIdKeySecretName: config.svApp?.svIdKeyGcpSecret,
    cometBftGovernanceKeySecretName: config.svApp?.cometBftGovernanceKeyGcpSecret,
    ...(config.validatorApp?.sweep
      ? { sweep: sweepConfigFromEnv(config.validatorApp.sweep.fromEnv) }
      : {}),
    ...(config.scanApp?.bigQuery
      ? { scanBigQuery: { dataset: 'devnet_da2_scan', prefix: 'da2' } }
      : {}),
  };
};

// to generate new keys: https://cimain.network.canton.global/sv_operator/sv_helm.html#generating-your-cometbft-node-keys
// TODO(DACH-NY/canton-network-internal#435): rotate the non-mainNet keys as they have been exposed in github (once mechanism is in place)
export const standardSvConfigs: StaticSvConfig[] = isMainNet
  ? [
      {
        // TODO(DACH-NY/canton-network-node#12169): consider making nodeName and ingressName the same (also for all other SVs)
        nodeName: 'sv-1',
        ingressName: 'sv-2', // fun, right?
        onboardingName: 'Digital-Asset-2',
        auth0ValidatorAppName: 'validator',
        auth0SvAppName: 'sv',
        cometBft: {
          nodeIndex: 1,
          id: '4c7c99516fb3309b89b7f8ed94690994c8ec0ab0',
          privateKey: svCometBftSecrets[0].nodePrivateKey,
          validator: {
            keyAddress: '9473617BBC80C12F68CC25B5A754D1ED9035886C',
            privateKey: svCometBftSecrets[0].validatorPrivateKey,
            publicKey: 'H2bcJU2zbzbLmP78YWiwMgtB0QG1MNTSozGl1tP11hI=',
          },
        },
        sweep: sweepConfigFromEnv('SV1'),
        ...(sv1ScanBigQuery
          ? { scanBigQuery: { dataset: 'mainnet_da2_scan', prefix: 'da2' } }
          : {}),
      },
    ]
  : [
      {
        // TODO(DACH-NY/canton-network-node#12169): consider making nodeName and ingressName the same (also for all other SVs)
        nodeName: 'sv-1',
        ingressName: 'sv-2', // fun, right?
        onboardingName: 'Digital-Asset-2',
        auth0ValidatorAppName: 'sv1_validator',
        auth0SvAppName: 'sv-1',
        validatorWalletUser: isDevNet
          ? 'auth0|64afbc0956a97fe9577249d7'
          : 'auth0|64529b128448ded6aa68048f',
        cometBft: {
          nodeIndex: 1,
          id: '5af57aa83abcec085c949323ed8538108757be9c',
          privateKey: svCometBftSecrets[0].nodePrivateKey,
          validator: {
            keyAddress: '8A931AB5F957B8331BDEF3A0A081BD9F017A777F',
            privateKey: svCometBftSecrets[0].validatorPrivateKey,
            publicKey: 'gpkwc1WCttL8ZATBIPWIBRCrb0eV4JwMCnjRa56REPw=',
          },
        },
        sweep: sweepConfigFromEnv('SV1'),
        ...(sv1ScanBigQuery ? { scanBigQuery: { dataset: 'devnet_da2_scan', prefix: 'da2' } } : {}),
      },
      {
        // TODO(DACH-NY/canton-network-node#12169): consider making nodeName and ingressName the same (also for all other SVs)
        nodeName: 'sv-2',
        ingressName: 'sv-2-eng',
        onboardingName: 'Digital-Asset-Eng-2',
        auth0ValidatorAppName: 'sv2_validator',
        auth0SvAppName: 'sv-2',
        validatorWalletUser: 'auth0|64afbc353bbc7ca776e27bf4',
        cometBft: {
          nodeIndex: 2,
          id: 'c36b3bbd969d993ba0b4809d1f587a3a341f22c1',
          privateKey: svCometBftSecrets[1].nodePrivateKey,
          validator: {
            keyAddress: '04A57312179F1E0C93B868779EE4C7FAC41666F0',
            privateKey: svCometBftSecrets[1].validatorPrivateKey,
            publicKey: 'BVSM9/uPGLU7lJj72SUw1a261z2L6Yy2XKLhpUvbxqE=',
          },
        },
      },
      {
        nodeName: 'sv-3',
        ingressName: 'sv-3-eng',
        onboardingName: 'Digital-Asset-Eng-3',
        auth0ValidatorAppName: 'sv3_validator',
        auth0SvAppName: 'sv-3',
        validatorWalletUser: 'auth0|64afbc4431b562edb8995da6',
        cometBft: {
          nodeIndex: 3,
          id: '0d8e87c54d199e85548ccec123c9d92966ec458c',
          privateKey: svCometBftSecrets[2].nodePrivateKey,
          validator: {
            keyAddress: 'FFF137F42421B0257CDC8B2E41F777B81A081E80',
            privateKey: svCometBftSecrets[2].validatorPrivateKey,
            publicKey: 'dxm4n1MRP/GuSEkJIwbdB4zVcGAeacohFKNtbKK8oRA=',
          },
        },
      },
      {
        nodeName: 'sv-4',
        ingressName: 'sv-4-eng',
        onboardingName: 'Digital-Asset-Eng-4',
        auth0ValidatorAppName: 'sv4_validator',
        auth0SvAppName: 'sv-4',
        validatorWalletUser: 'auth0|64afbc720e20777e46fff490',
        cometBft: {
          nodeIndex: 4,
          id: 'ee738517c030b42c3ff626d9f80b41dfc4b1a3b8',
          privateKey: svCometBftSecrets[3].nodePrivateKey,
          validator: {
            keyAddress: 'DE36D23DE022948A11200ABB9EE07F049D17D903',
            privateKey: svCometBftSecrets[3].validatorPrivateKey,
            publicKey: '2umZdUS97a6VUXMGsgKJ/VbQbanxWaFUxK1QimhlEjo=',
          },
        },
      },
      {
        nodeName: 'sv-5',
        ingressName: 'sv-5-eng',
        onboardingName: 'Digital-Asset-Eng-5',
        auth0ValidatorAppName: 'sv5_validator',
        auth0SvAppName: 'sv-5',
        validatorWalletUser: 'auth0|65c15c482a18b1ef030ba290',
        cometBft: {
          nodeIndex: 5,
          id: '205437468610305149d131bbf9bf1f47658d861b',
          privateKey: svCometBftSecrets[4].nodePrivateKey,
          validator: {
            keyAddress: '1A6C9E60AFD830682CBEF5496F6E5515B20B0F2D',
            privateKey: svCometBftSecrets[4].validatorPrivateKey,
            publicKey: 'ykypzmTJei5w+DiNM67nCfb06FMpHliYU7FXpxDYJgY=',
          },
        },
      },
      {
        nodeName: 'sv-6',
        ingressName: 'sv-6-eng',
        onboardingName: 'Digital-Asset-Eng-6',
        auth0ValidatorAppName: 'sv6_validator',
        auth0SvAppName: 'sv-6',
        validatorWalletUser: 'auth0|65c26e959666d60d24fe523a',
        cometBft: {
          nodeIndex: 6,
          id: '60c21490e82d6a1fb0c35b9a04e4f64ae00ce5c0',
          privateKey: svCometBftSecrets[5].nodePrivateKey,
          validator: {
            keyAddress: 'DC41F08916D8C41B931F9037E6F2571C58D0E01A',
            privateKey: svCometBftSecrets[5].validatorPrivateKey,
            publicKey: 'wAFEjO8X4qaD6dRM1TSvWjX+SMXoLEqIIjqqWUi1ETI=',
          },
        },
      },
      {
        nodeName: 'sv-7',
        ingressName: 'sv-7-eng',
        onboardingName: 'Digital-Asset-Eng-7',
        auth0ValidatorAppName: 'sv7_validator',
        auth0SvAppName: 'sv-7',
        validatorWalletUser: 'auth0|65c26e9d45eaef5c191a167e',
        cometBft: {
          nodeIndex: 7,
          id: '81f3b7d26ae796d369fbf42481a65c6265b41e8c',
          privateKey: svCometBftSecrets[6].nodePrivateKey,
          validator: {
            keyAddress: '66FA9399FF2E7AF2517E7CE2EDCA11F51C573F61',
            privateKey: svCometBftSecrets[6].validatorPrivateKey,
            publicKey: 'aWWSRgIAJSc3pPaz89zu2yEyqRuKY5SY8Evpt/klt74=',
          },
        },
      },
      {
        nodeName: 'sv-8',
        ingressName: 'sv-8-eng',
        onboardingName: 'Digital-Asset-Eng-8',
        auth0ValidatorAppName: 'sv8_validator',
        auth0SvAppName: 'sv-8',
        validatorWalletUser: 'auth0|65c26ea449ef8564a0ec9297',
        cometBft: {
          nodeIndex: 8,
          id: '404371a5f62773ca07925555c9fbb6287861947c',
          privateKey: svCometBftSecrets[7].nodePrivateKey,
          validator: {
            keyAddress: '5E35AE8D464FA92525BCC408C7827A943BDF4900',
            privateKey: svCometBftSecrets[7].validatorPrivateKey,
            publicKey: '/W/bfGC9S0VeKtx5ID9HFJ4JO8dSbnY/wE8J+yESOxY=',
          },
        },
      },
      {
        nodeName: 'sv-9',
        ingressName: 'sv-9-eng',
        onboardingName: 'Digital-Asset-Eng-9',
        auth0ValidatorAppName: 'sv9_validator',
        auth0SvAppName: 'sv-9',
        validatorWalletUser: 'auth0|65c26eac58f141b4ca1dc5da',
        cometBft: {
          nodeIndex: 9,
          id: 'aeee969d0efb0784ea36b9ad743a2e5964828325',
          privateKey: svCometBftSecrets[8].nodePrivateKey,
          validator: {
            keyAddress: '06070D2FD47073BE1635C3DEB862A88669906847',
            privateKey: svCometBftSecrets[8].validatorPrivateKey,
            publicKey: 'rkd3pJH+kwrDt9i8b3I9c1RqznnsFe5PueE3gB5nZg8=',
          },
        },
      },
      {
        nodeName: 'sv-10',
        ingressName: 'sv-10-eng',
        onboardingName: 'Digital-Asset-Eng-10',
        auth0ValidatorAppName: 'sv10_validator',
        auth0SvAppName: 'sv-10',
        validatorWalletUser: 'auth0|65e0a7854c76b74b28b8477f',
        cometBft: {
          nodeIndex: 10,
          id: 'cc8e74ca2c3c66820266dc6cca759f5368dd9924',
          privateKey: svCometBftSecrets[9].nodePrivateKey,
          validator: {
            keyAddress: 'E71220096CC607150D56914B9175A5D4B70B00E6',
            privateKey: svCometBftSecrets[9].validatorPrivateKey,
            publicKey: '9aJvIAkmKWiKGzLY354fA3nWPL62X2Ye5b52bmGEtMI=',
          },
        },
      },
      {
        nodeName: 'sv-11',
        ingressName: 'sv-11-eng',
        onboardingName: 'Digital-Asset-Eng-11',
        auth0ValidatorAppName: 'sv11_validator',
        auth0SvAppName: 'sv-11',
        validatorWalletUser: 'auth0|65e0a78976d9757e3f14846b',
        cometBft: {
          nodeIndex: 11,
          id: '21f60b2667972ff943fbd46ea9ca82ddf0905948',
          privateKey: svCometBftSecrets[10].nodePrivateKey,
          validator: {
            keyAddress: '14474E591E9C75E5FCA4520B36CD4963E2FBAA2C',
            privateKey: svCometBftSecrets[10].validatorPrivateKey,
            publicKey: 'cSNIpvKpUVdnpDh7m0zhZXRhX4MTRlZeYDnwl47mLrM=',
          },
        },
      },
      {
        nodeName: 'sv-12',
        ingressName: 'sv-12-eng',
        onboardingName: 'Digital-Asset-Eng-12',
        auth0ValidatorAppName: 'sv12_validator',
        auth0SvAppName: 'sv-12',
        validatorWalletUser: 'auth0|65e0a78d68c39e5cc0351ed2',
        cometBft: {
          nodeIndex: 12,
          id: '817bb28c471d7a8631e701c914fc7e9a65e74be2',
          privateKey: svCometBftSecrets[11].nodePrivateKey,
          validator: {
            keyAddress: '1E5F191A4E2C4DD5026A3B26F1F66A809D5D4E8C',
            privateKey: svCometBftSecrets[11].validatorPrivateKey,
            publicKey: 'F0cuaIrJU4NfTmtpqVP6y6oReJh2WSuB9YWDKtSR2wU=',
          },
        },
      },
      {
        nodeName: 'sv-13',
        ingressName: 'sv-13-eng',
        onboardingName: 'Digital-Asset-Eng-13',
        auth0ValidatorAppName: 'sv13_validator',
        auth0SvAppName: 'sv-13',
        validatorWalletUser: 'auth0|65e0a7914c76b74b28b84793',
        cometBft: {
          nodeIndex: 13,
          id: '254dd73eb4cee23d439c2f2e706ccdbeac52f06c',
          privateKey: svCometBftSecrets[12].nodePrivateKey,
          validator: {
            keyAddress: 'CFF50F6EFD5DFDD8DAD7A468D5FB5DA2D43CF281',
            privateKey: svCometBftSecrets[12].validatorPrivateKey,
            publicKey: '6ltWNxHRrwPj9qPYB3HQWL4hpeFTCjHSW2m+7rCYWAw=',
          },
        },
      },
      {
        nodeName: 'sv-14',
        ingressName: 'sv-14-eng',
        onboardingName: 'Digital-Asset-Eng-14',
        auth0ValidatorAppName: 'sv14_validator',
        auth0SvAppName: 'sv-14',
        validatorWalletUser: 'auth0|65e0a795aa7a40df0cc65ace',
        cometBft: {
          nodeIndex: 14,
          id: '9de44f8ddac42901c094371e867bb0db60ab03b8',
          privateKey: svCometBftSecrets[13].nodePrivateKey,
          validator: {
            keyAddress: 'F691F4CA91B972A6B291C09BADA9970AAAC86C84',
            privateKey: svCometBftSecrets[13].validatorPrivateKey,
            publicKey: 'rP4eWO4WZctUrQE5ZDFHkXxCWfZa6tc8B8qLmrzV7gE=',
          },
        },
      },
      {
        nodeName: 'sv-15',
        ingressName: 'sv-15-eng',
        onboardingName: 'Digital-Asset-Eng-15',
        auth0ValidatorAppName: 'sv15_validator',
        auth0SvAppName: 'sv-15',
        validatorWalletUser: 'auth0|65e0a7994c76b74b28b8479c',
        cometBft: {
          nodeIndex: 15,
          id: '7a5f4f9ee97ec24bb4a1a6ed22ec3676805fa494',
          privateKey: svCometBftSecrets[14].nodePrivateKey,
          validator: {
            keyAddress: 'AAE830BF1289910D20E646D9B69561D9E0F965EA',
            privateKey: svCometBftSecrets[14].validatorPrivateKey,
            publicKey: 'iAxMTvCLe/YO4cP9+RocTxw7+lEsGxsiiPc2hMq6oLs=',
          },
        },
      },
      {
        nodeName: 'sv-16',
        ingressName: 'sv-16-eng',
        onboardingName: 'Digital-Asset-Eng-16',
        auth0ValidatorAppName: 'sv16_validator',
        auth0SvAppName: 'sv-16',
        validatorWalletUser: 'auth0|65e0a79de124e5c43dcb6a19',
        cometBft: {
          nodeIndex: 16,
          id: '9831eeb365f221034e70f27c5073ee0857bdc945',
          privateKey: svCometBftSecrets[15].nodePrivateKey,
          validator: {
            keyAddress: '0C77119A80F4B4305729D49EC76FC7D4C0576229',
            privateKey: svCometBftSecrets[15].validatorPrivateKey,
            publicKey: '+cNplFRLm7gBS/hIsJrWVcDtfGoCQ2Yb4HCzvBqYdZ0=',
          },
        },
      },
    ];

// TODO(#1892): consider supporting overrides of hardcoded svs (in case we're keeping hardcoded svs at all)
export const extraSvConfigs: StaticSvConfig[] = configuredExtraSvs.map((k, index) =>
  // Note how we give the first extra SV the CometBFT node index of the first standard SV that we don't deploy.
  fromSingleSvConfig(k, dsoSize + index + 1)
);

export const svConfigs = standardSvConfigs.concat(extraSvConfigs);

export const sv1Config: StaticSvConfig = standardSvConfigs[0];

export const svRunbookConfig: StaticSvConfig = {
  onboardingName: 'DA-Helm-Test-Node',
  nodeName: 'sv',
  ingressName: 'sv',
  auth0SvAppName: 'sv',
  auth0ValidatorAppName: 'validator',
  // Default to admin@sv-dev.com (devnet) or admin@sv.com (non devnet) at the sv-test tenant by default
  validatorWalletUser: isDevNet
    ? 'auth0|64b16b9ff7a0dfd00ea3704e'
    : 'auth0|64553aa683015a9687d9cc2e',
  cometBft: {
    id: '9116f5faed79dcf98fa79a2a40865ad9b493f463',
    nodeIndex: 0,
    validator: {
      keyAddress: '0647E4FF27908B8B874C2647536AC986C9EA0BAB',
    },
  },
};

export function sweepConfigFromEnv(nodeName: string): SweepConfig | undefined {
  const asJson = spliceEnvConfig.optionalEnv(`${nodeName}_SWEEP`);
  return asJson && JSON.parse(asJson);
}

// "core SVs" are deployed as part of the `canton-network` stack;
// if config.yaml contains any SVs that don't match the standard sv-X pattern, we deploy them independently of DSO_SIZE
export const coreSvsToDeploy: StaticSvConfig[] = standardSvConfigs
  .slice(0, dsoSize)
  .concat(skipExtraSvs ? [] : extraSvConfigs);

export const allSvsToDeploy = coreSvsToDeploy.concat(DeploySvRunbook ? [svRunbookConfig] : []);
