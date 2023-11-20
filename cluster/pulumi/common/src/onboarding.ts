import * as k8s from '@pulumi/kubernetes';

import { btoa, ExactNamespace } from './utils';

export type ExpectedValidatorOnboarding = { name: string; expiresIn: string; secret: string };

export const validatorOnboardingSecretName = (name: string): string =>
  `cn-app-validator-onboarding-${name}`;

export function installValidatorOnboardingSecret(
  xns: ExactNamespace,
  name: string,
  secret: string
): k8s.core.v1.Secret {
  const secretName = validatorOnboardingSecretName(name);
  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-validator-onboarding-${name}`,
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
