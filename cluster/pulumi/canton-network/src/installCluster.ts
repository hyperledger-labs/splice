// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  config,
  DecentralizedSynchronizerUpgradeConfig,
  ExpectedValidatorOnboarding,
  isDevNet,
  svOnboardingPollingInterval,
  svValidatorTopupConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { readBackupConfig } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/backup';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
  splitwellOnboarding,
  standaloneValidatorOnboarding,
  validator1Onboarding,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validators';
import { SplitPostgresInstances } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configs';
import { Resource } from '@pulumi/pulumi';

import { activeVersion } from '../../common';
import { installChaosMesh } from './chaosMesh';
import { installDocs } from './docs';
import { Dso } from './dso';

/// Toplevel Chart Installs

console.error(`Launching with isDevNet: ${isDevNet}`);

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

const disableOnboardingParticipantPromotionDelay = config.envFlag(
  'DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY',
  false
);

export async function installCluster(
  auth0Client: Auth0Client
): Promise<{ dso: Dso; validator1?: Resource }> {
  console.error(
    activeVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the container registry by default, version ${activeVersion.version}`
  );

  const backupConfig = await readBackupConfig();
  const expectedValidatorOnboardings: ExpectedValidatorOnboarding[] = [];
  if (mustInstallSplitwell) {
    expectedValidatorOnboardings.push(splitwellOnboarding);
  }
  if (mustInstallValidator1) {
    expectedValidatorOnboardings.push(validator1Onboarding);
  }
  if (standaloneValidatorOnboarding) {
    expectedValidatorOnboardings.push(standaloneValidatorOnboarding);
  }

  const dso = new Dso('dso', {
    auth0Client,
    expectedValidatorOnboardings,
    isDevNet,
    ...backupConfig,
    topupConfig: svValidatorTopupConfig,
    splitPostgresInstances: SplitPostgresInstances,
    decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerUpgradeConfig,
    onboardingPollingInterval: svOnboardingPollingInterval,
    disableOnboardingParticipantPromotionDelay,
  });

  const allSvs = await dso.allSvs;

  const svDependencies = allSvs.flatMap(sv => [sv.scan, sv.svApp, sv.validatorApp, sv.ingress]);

  installDocs();

  if (enableChaosMesh) {
    installChaosMesh({ dependsOn: svDependencies });
  }

  return {
    dso,
  };
}
