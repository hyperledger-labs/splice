// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  DecentralizedSynchronizerUpgradeConfig,
  isDevNet,
  nonDevNetNonSvValidatorTopupConfig,
  nonSvValidatorTopupConfig,
} from 'splice-pulumi-common';
import { readBackupConfig } from 'splice-pulumi-common-validator/src/backup';
import { splitwellOnboarding } from 'splice-pulumi-common-validator/src/validators';
import { SplitPostgresInstances } from 'splice-pulumi-common/src/config/configs';

import { installSplitwell } from './splitwell';

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  const backupConfig = await readBackupConfig();
  await installSplitwell(
    auth0Client,
    'auth0|63e12e0415ad881ffe914e61',
    'auth0|65de04b385816c4a38cc044f',
    splitwellOnboarding.secret,
    SplitPostgresInstances,
    DecentralizedSynchronizerUpgradeConfig,
    backupConfig.periodicBackupConfig,
    backupConfig.bootstrappingDumpConfig,
    isDevNet ? nonSvValidatorTopupConfig : nonDevNetNonSvValidatorTopupConfig
  );
}
