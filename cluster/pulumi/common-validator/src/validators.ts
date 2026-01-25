// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  config,
  ExpectedValidatorOnboarding,
  preApproveValidatorRunbook,
} from '@lfdecentralizedtrust/splice-pulumi-common';

export const mustInstallValidator1 = config.envFlag('SPLICE_DEPLOY_VALIDATOR1', true);

export const mustInstallSplitwell = config.envFlag('SPLICE_DEPLOY_SPLITWELL', true);

export const splitwellOnboarding = {
  name: 'splitwell2',
  secret: 'splitwellsecret2',
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
