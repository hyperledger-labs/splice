// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ApprovedSvIdentity,
  Auth0Client,
  config,
  DecentralizedSynchronizerUpgradeConfig,
  ExpectedValidatorOnboarding,
  isDevNet,
  sequencerPruningConfig,
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

// This flag determines whether to add an approved SV entry of 'DA-Helm-Test-Node'.
// This flag is not relevant if you're using an `approved-sv-id-values.yaml` file that
// already contains a 'DA-Helm-Test-Node' entry.
const approveSvRunbook = config.envFlag('APPROVE_SV_RUNBOOK');
if (approveSvRunbook) {
  console.error('Approving SV used in SV runbook');
}

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

const disableOnboardingParticipantPromotionDelay = config.envFlag(
  'DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY',
  false
);

const svRunbookApprovedSvIdentities: ApprovedSvIdentity[] = [
  {
    name: 'DA-Helm-Test-Node',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==',
    rewardWeightBps: 10000,
  },
];

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
    approvedSvIdentities: approveSvRunbook ? svRunbookApprovedSvIdentities : [],
    expectedValidatorOnboardings,
    isDevNet,
    ...backupConfig,
    topupConfig: svValidatorTopupConfig,
    splitPostgresInstances: SplitPostgresInstances,
    sequencerPruningConfig,
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
