import {
  config,
  ExpectedValidatorOnboarding,
  preApproveValidatorRunbook,
} from 'splice-pulumi-common';

export const mustInstallValidator1 = config.envFlag('CN_INSTALL_VALIDATOR1', true);

export const mustInstallSplitwell = config.envFlag('CN_INSTALL_SPLITWELL', true);

export const splitwellOnboarding = {
  name: 'splitwell',
  secret: 'splitwellsecret',
  expiresIn: '24h',
};

export const validator1Onboarding = {
  name: 'validator12',
  secret: 'validator1secret2',
  expiresIn: '24h',
};

export const standaloneValidatorOnboarding: ExpectedValidatorOnboarding | undefined =
  preApproveValidatorRunbook
    ? {
        name: 'validator',
        secret: 'validatorsecret',
        expiresIn: '24h',
      }
    : undefined;
