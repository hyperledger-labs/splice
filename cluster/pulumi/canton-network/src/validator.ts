import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVar,
  installAuth0Secret,
  installAuth0UISecret,
  exactNamespace,
  installCNHelmChart,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { domainFeesConfig } from './domainFeesCfg';
import { installParticipant } from './ledger';
import {
  ValidatorOnboarding,
  installValidatorOnboardingSecret,
  validatorOnboardingSecretName,
} from './sv';

export async function installValidator(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  name: string,
  validatorWalletUser: string,
  onboarding: ValidatorOnboarding,
  withDomainFees = false
): Promise<pulumi.Resource> {
  const xns = exactNamespace(name);

  const postgresDb = postgres.installPostgres(xns, 'postgres');

  const participant = installParticipant(
    xns,
    'participant',
    postgresDb,
    [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
    [
      {
        actAs: [],
        admin: true,
        name: {
          env: 'CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME',
        },
        readAs: [],
      },
    ],
    [auth0UserNameEnvVar('validator')]
  );

  installCNHelmChart(xns, 'splitwell-web-ui', 'cn-splitwell-web-ui', {}, [
    await installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell'),
  ]);

  const dependsOn = [
    svc,
    xns.ns,
    participant,
    await installAuth0Secret(auth0Client, xns, 'validator', 'validator'),
    await installAuth0UISecret(auth0Client, xns, 'wallet', 'wallet'),
    await installAuth0UISecret(auth0Client, xns, 'directory', 'directory'),
    installValidatorOnboardingSecret(xns, onboarding),
  ];

  return installCNHelmChart(
    xns,
    'validator-' + xns.logicalName,
    'cn-validator',
    {
      participantAddress: 'participant',
      postgres: postgresDb,
      additionalUsers: [],
      validatorPartyHint: `${name}_validator_service_user`,
      appDars: [
        'cn-node-0.1.0-SNAPSHOT/dars/directory-service-0.1.0.dar',
        'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar',
      ],
      globalDomainUrl: 'http://global-domain-sequencer.sv-1:5008',
      validatorWalletUser,
      foundingSvApiUrl: 'http://sv-app.sv-1:5014',
      svSponsorAddress: 'http://sv-app.sv-1:5014',
      onboardingSecretFrom: {
        secretKeyRef: {
          name: validatorOnboardingSecretName(onboarding),
          key: 'secret',
          optional: false,
        },
      },
      topup: withDomainFees
        ? {
            enabled: true,
            targetThroughput: domainFeesConfig.targetThroughput,
            minTopupInterval: domainFeesConfig.minTopupInterval,
          }
        : {},
    },
    dependsOn
  );
}
