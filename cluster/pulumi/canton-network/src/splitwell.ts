import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVar,
  auth0UserNameEnvVarSource,
  installAuth0Secret,
  exactNamespace,
  installCNHelmChart,
  CLUSTER_BASENAME,
  ValidatorTopupConfig,
  ExactNamespace,
  sanitizedForPostgres,
} from 'cn-pulumi-common';
import type { Auth0Client, BackupConfig, BootstrappingDumpConfig } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import * as postgres from './postgres';
import { DomainIndex } from './globalDomainNode';
import { installParticipant } from './ledger';
import { Postgres, installPostgresMetrics } from './postgres';
import { installValidatorApp } from './validator';

export async function installSplitwell(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  providerWalletUser: string,
  onboardingSecret: string,
  splitPostgresInstances: boolean,
  svActiveDomain: DomainIndex,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace('splitwell', true);

  const domainPostgres = postgres.installPostgres(
    xns,
    splitPostgresInstances ? 'domain-pg' : 'postgres',
    splitPostgresInstances
  );

  const domain = installDomain(xns, 'domain', domainPostgres);

  const loopback = installCNHelmChart(
    xns,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        basename: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [xns.ns] }
  );

  const participantPostgres = splitPostgresInstances
    ? postgres.installPostgres(xns, 'participant-pg', true)
    : domainPostgres;

  const participant = installParticipant(
    xns,
    'participant',
    participantPostgres,
    auth0UserNameEnvVarSource('validator'),
    // We disable auto-init if we have a dump to bootstrap from.
    !!participantBootstrapDump,
    [domain, loopback]
  );

  const swPostgres = splitPostgresInstances
    ? postgres.installPostgres(xns, 'sw-pg', true)
    : domainPostgres;

  const globalDomainUrl = `https://sequencer.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`;
  const scanAddress = `http://scan-app-${svActiveDomain}.sv-1:5012`;
  installCNHelmChart(
    xns,
    'splitwell-app',
    'cn-splitwell-app',
    {
      postgres: swPostgres.address,
      metrics: {
        enable: true,
      },
      scanAddress: scanAddress,
    },
    { dependsOn: [svc, participant] }
  );

  const validatorPostgres = splitPostgresInstances
    ? postgres.installPostgres(xns, 'validator-pg', true)
    : domainPostgres;
  const validatorDbName = 'val_splitwell';

  const extraDependsOn = [
    svc,
    await installAuth0Secret(auth0Client, xns, 'splitwell', 'splitwell'),
  ];

  const validator = installValidatorApp({
    auth0Client,
    xns,
    extraDependsOn,
    participant,
    additionalUsers: [
      auth0UserNameEnvVar('splitwell'),
      { name: 'CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME', value: providerWalletUser },
    ],
    extraDomains: [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
    additionalConfig: [
      'canton.validator-apps.validator_backend.app-instances.splitwise = {',
      '  service-user = ${?CN_APP_SPLITWELL_LEDGER_API_AUTH_USER_NAME}',
      '  wallet-user = ${?CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME}',
      '  dars = ["cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar"]',
      '}',
    ].join('\n'),
    onboardingSecret,
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    svSponsorAddress: `http://sv-app-${svActiveDomain}.sv-1:5014`,
    auth0AppName: 'splitwell_validator',
    participantBootstrapDump,
    participantAddress: 'participant',
    topupConfig: topupConfig,
    svValidator: false,
    persistenceConfig: {
      host: validatorPostgres.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: validatorPostgres.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
    },
    scanAddress: scanAddress,
    globalDomainUrl: globalDomainUrl,
  });

  installPostgresMetrics(validatorPostgres, validatorDbName, [validator]);

  return validator;
}

function installDomain(xns: ExactNamespace, name: string, postgres: Postgres): pulumi.Resource {
  const sanitizedName = sanitizedForPostgres(name);
  const mediatorDbName = `${sanitizedName}_mediator`;
  const sequencerDbName = `${sanitizedName}_sequencer`;

  const domain = installCNHelmChart(xns, name, 'cn-domain', {
    mediator: {
      persistence: {
        databaseName: mediatorDbName,
        host: postgres.address,
        secretName: postgres.secretName,
      },
    },
    sequencer: {
      persistence: {
        databaseName: sequencerDbName,
        host: postgres.address,
        secretName: postgres.secretName,
      },
    },
    additionalJvmOptions: jmxOptions(),
  });

  installPostgresMetrics(postgres, mediatorDbName, [domain]);
  installPostgresMetrics(postgres, sequencerDbName, [domain]);

  return domain;
}
