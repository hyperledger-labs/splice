import * as k8s from '@pulumi/kubernetes';

import { btoa, ExactNamespace } from './utils';

export type ValidatorOnboarding = { name: string; expiresIn: string; secret: string };

export const validatorOnboardingSecretName = (onboarding: ValidatorOnboarding): string =>
  `cn-app-validator-onboarding-${onboarding.name}`;

export function installValidatorOnboardingSecret(
  xns: ExactNamespace,
  onboarding: ValidatorOnboarding
): k8s.core.v1.Secret {
  const secretName = validatorOnboardingSecretName(onboarding);
  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-validator-onboarding-${onboarding.name}`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        secret: btoa(onboarding.secret),
      },
    },
    {
      dependsOn: [xns.ns],
    }
  );
}
