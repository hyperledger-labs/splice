import { Output } from '@pulumi/pulumi';
import { ExpectedValidatorOnboarding, isDevNet } from 'cn-pulumi-common';

// import { NodeConfig } from './cometbft';
import { SvOnboarding } from './sv';

type SvConf = {
  nodeName: string;
  onboardingName: string;
  validatorWalletUser: string;
  onboarding: SvOnboarding;
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[];
  auth0ValidatorAppName: string;
  cometBft: NodeConfig;
};

type NodeConfig = {
  privateKey: Output<string> | string;
  validator: {
    keyAddress: Output<string> | string;
    privateKey: Output<string> | string;
    publicKey: Output<string> | string;
  };
  nodeIndex: number;
  retainBlocks?: number;
  id: Output<string> | string;
};

const splitwellOnboarding = {
  name: 'splitwell',
  secret: 'splitwellsecret',
  expiresIn: '24h',
};

const validator1Onboarding = {
  name: 'validator1',
  secret: 'validator1secret',
  expiresIn: '24h',
};

const standaloneValidatorOnboarding = {
  name: 'validator',
  secret: 'validatorsecret',
  expiresIn: '24h',
};

export const svConfs: SvConf[] = [
  {
    nodeName: 'sv-1',
    onboarding: { type: 'found-collective' },
    onboardingName: isDevNet ? 'Canton-Foundation-1' : 'Canton-Foundation',
    auth0ValidatorAppName: 'sv1_validator',
    validatorWalletUser: isDevNet
      ? 'auth0|64afbc0956a97fe9577249d7'
      : 'auth0|64529b128448ded6aa68048f',
    expectedValidatorOnboardings: [
      splitwellOnboarding,
      validator1Onboarding,
      standaloneValidatorOnboarding,
    ],
    cometBft: {
      nodeIndex: 1,
      id: '5af57aa83abcec085c949323ed8538108757be9c',
      privateKey:
        '/7L74Bs18740fTPdEL04BeO2Gs+1lzEeCjAiB1DYcysmLnU1FAkg/Ho9XsOiIp4U/KT/YNrtIi/A0prm/Ew3eQ==',
      validator: {
        keyAddress: '8A931AB5F957B8331BDEF3A0A081BD9F017A777F',
        privateKey:
          'npgiYbG0Iaslb/JHzliAg5BkfYMOaK3tCdKWvvO4FjCCmTBzVYK20vxkBMEg9YgFEKtvR5XgnAwKeNFrnpEQ/A==',
        publicKey: 'gpkwc1WCttL8ZATBIPWIBRCrb0eV4JwMCnjRa56REPw=',
      },
    },
  },
];
