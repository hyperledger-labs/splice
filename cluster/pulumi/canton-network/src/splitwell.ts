import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVar,
  auth0UserNameEnvVarSource,
  installAuth0Secret,
  installAuth0UISecret,
  exactNamespace,
  fixedTokens,
  installCNHelmChart,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { BackupConfig, installGcpBucketSecret } from './backup';
import { domainFeesConfig } from './domainFeesCfg';
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
  onboarding: ValidatorOnboarding,
  withDomainFees = false,
  postgresPassword: pulumi.Input<string>,
  backupConfig?: BackupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace('splitwell');

  const postgresDb = postgres.installPostgres(xns, 'postgres', postgresPassword);

  const domain = installDomain(xns, 'domain', postgresDb, postgresPassword);

  const participant = installParticipant(
    xns,
    'participant',
    postgresDb,
    auth0UserNameEnvVarSource('validator'),
    postgresPassword,
    [domain]
  );

  installCNHelmChart(
    xns,
    'splitwell-app',
    'cn-splitwell-app',
    {
      postgres: postgresDb,
      postgresPassword,
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
  ].concat(backupConfig ? [installGcpBucketSecret(xns, backupConfig.bucket)] : []);

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
      extraDomains: [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
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
      topup: withDomainFees
        ? {
            enabled: true,
            targetThroughput: domainFeesConfig.targetThroughput,
            minTopupInterval: domainFeesConfig.minTopupInterval,
          }
        : {},
      participantIdentitiesBackup: backupConfig,
    },
    dependsOn
  );
}
