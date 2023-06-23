import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVar,
  installAuth0Secret,
  installAuth0UISecret,
  exactNamespace,
  fixedTokens,
  installCNHelmChart,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installDomain, installParticipant } from './ledger';
import {
  ValidatorOnboarding,
  installValidatorOnboardingSecret,
  validatorOnboardingSecretName,
} from './sv';

export async function installSplitwell(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  providerWalletUser: string,
  onboarding: ValidatorOnboarding
): Promise<pulumi.Resource> {
  const xns = exactNamespace('splitwell');

  const postgresDb = postgres.installPostgres(xns, 'postgres');

  const domain = installDomain(xns, 'domain', postgresDb);

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
          env: 'CN_APP_SPLITWELL_VALIDATOR_LEDGER_API_AUTH_USER_NAME',
        },
        readAs: [],
      },
    ],
    [auth0UserNameEnvVar('splitwell_validator', 'validator')],
    [domain]
  );

  installCNHelmChart(
    xns,
    'splitwell-app',
    'cn-splitwell-app',
    {
      postgres: postgresDb,
    },
    [svc, participant]
  );

  const dependsOn = [
    svc,
    await installAuth0Secret(auth0Client, xns, 'splitwell', 'splitwell'),
    await installAuth0Secret(auth0Client, xns, 'validator', 'splitwell_validator'),
    await installAuth0UISecret(auth0Client, xns, 'wallet', 'splitwell'),
    await installAuth0UISecret(auth0Client, xns, 'directory', 'directory'),
    installValidatorOnboardingSecret(xns, onboarding),
  ];

  const fixedTokenConfig = fixedTokens()
    ? [
        '_client_credentials_auth_config = null',
        '_client_credentials_auth_config = {',
        '  type = "static"',
        '  token = ${CN_APP_VALIDATOR_LEDGER_API_AUTH_TOKEN}',
        '}',
      ]
    : [];

  return installCNHelmChart(
    xns,
    'splitwell',
    'cn-validator',
    {
      postgres: postgresDb,
      additionalUsers: [
        auth0UserNameEnvVar('splitwell'),
        { name: 'CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME', value: providerWalletUser },
      ],
      globalDomainUrl: 'http://global-domain-sequencer.sv-1:5008',
      additionalConfig: [
        ...fixedTokenConfig,
        'canton.validator-apps.validator_backend.app-instances.splitwise = {',
        '  service-user = ${?CN_APP_SPLITWELL_LEDGER_API_AUTH_USER_NAME}',
        '  wallet-user = ${?CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME}',
        '  dars = ["cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar"]',
        '}',
      ].join('\n'),
      foundingSvApiUrl: 'http://sv-app.sv-1:5014',
      svSponsorAddress: 'http://sv-app.sv-1:5014',
      onboardingSecretFrom: {
        secretKeyRef: {
          name: validatorOnboardingSecretName(onboarding),
          key: 'secret',
          optional: false,
        },
      },
    },
    dependsOn
  );
}
