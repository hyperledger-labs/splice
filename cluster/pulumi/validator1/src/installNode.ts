// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  DecentralizedSynchronizerUpgradeConfig,
  isDevNet,
  nonDevNetNonSvValidatorTopupConfig,
  nonSvValidatorTopupConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { readBackupConfig } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/backup';
import { autoAcceptTransfersConfigFromEnv } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validator';
import {
  mustInstallSplitwell,
  validator1Onboarding,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validators';
import { SplitPostgresInstances } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configs';

import { validator1Config } from './config';
import { installValidator1 } from './validator1';

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  const topupConfig = isDevNet ? nonSvValidatorTopupConfig : nonDevNetNonSvValidatorTopupConfig;
  const backupConfig = await readBackupConfig();
  await installValidator1(
    auth0Client,
    'validator1',
    validator1Onboarding.secret,
    validator1Config?.disableAuth ? 'administrator' : 'auth0|63e3d75ff4114d87a2c1e4f5',
    SplitPostgresInstances,
    DecentralizedSynchronizerUpgradeConfig,
    mustInstallSplitwell,
    backupConfig.periodicBackupConfig,
    backupConfig.bootstrappingDumpConfig,
    {
      ...topupConfig,
      // x10 validator1's traffic targetThroughput for load tester -- see #9064
      targetThroughput: topupConfig.targetThroughput * 10,
    },
    autoAcceptTransfersConfigFromEnv('VALIDATOR1')
  );
}
