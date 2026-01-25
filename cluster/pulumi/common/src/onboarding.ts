// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';

import { config } from './config';
import { btoa, ExactNamespace } from './utils';

export type ExpectedValidatorOnboarding = { name: string; expiresIn: string; secret: string };

export const validatorOnboardingSecretName = (name: string): string =>
  `splice-app-validator-onboarding-${name}`;

export const preApproveValidatorRunbook = config.envFlag('PREAPPROVE_VALIDATOR_RUNBOOK');

export function installValidatorOnboardingSecret(
  xns: ExactNamespace,
  name: string,
  secret: string
): k8s.core.v1.Secret {
  const secretName = validatorOnboardingSecretName(name);
  return new k8s.core.v1.Secret(
    `splice-app-${xns.logicalName}-validator-onboarding-${name}`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        secret: btoa(secret),
      },
    },
    {
      dependsOn: [xns.ns],
    }
  );
}
