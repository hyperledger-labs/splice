// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  DecentralizedSynchronizerUpgradeConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { validatorStableOldOnboarding } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validators';

import { installValidatorStableOld } from './validatorStableOld';

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  await installValidatorStableOld(
    auth0Client,
    'auth0|65de04b385816c4a38cc044f',
    validatorStableOldOnboarding.secret,
    DecentralizedSynchronizerUpgradeConfig
  );
}
