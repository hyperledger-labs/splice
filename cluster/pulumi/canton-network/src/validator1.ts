import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVarSource,
  installAuth0UISecret,
  exactNamespace,
  installCNHelmChart,
  BackupConfig,
  BootstrappingDumpConfig,
  ValidatorTopupConfig,
  CLUSTER_BASENAME,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import * as postgres from './postgres';
import { installParticipant } from './ledger';
import { installValidatorApp } from './validator';

export async function installValidator1(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  name: string,
  onboardingSecret: string,
  validatorWalletUser: string,
  splitPostgresInstances: boolean,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace(name, true);

  const participantPostgres = postgres.installPostgres(
    xns,
    splitPostgresInstances ? 'participant-pg' : 'postgres',
    splitPostgresInstances
  );

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

  const participant = installParticipant(
    xns,
    'participant',
    participantPostgres,
    auth0UserNameEnvVarSource('validator'),
    // We disable auto-init if we have a dump to bootstrap from.
    !!participantBootstrapDump,
    [loopback]
  );

  installCNHelmChart(
    xns,
    'splitwell-web-ui',
    'cn-splitwell-web-ui',
    {},
    {
      dependsOn: [await installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell')],
    }
  );

  const validatorPostgres = splitPostgresInstances
    ? postgres.installPostgres(xns, 'validator-pg', true)
    : participantPostgres;

  const validatorDbName = 'validator1';
  const validatorDb = validatorPostgres.createDatabaseAndInstallMetrics(validatorDbName);

  const extraDependsOn: pulumi.Resource[] = [
    svc,
    participantPostgres,
    validatorPostgres,
    validatorDb,
  ];

  return installValidatorApp({
    auth0Client,
    validatorWalletUser,
    xns,
    participant,
    appDars: ['cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar'],
    validatorPartyHint: `${name}_validator_service_user`,
    extraDomains: [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
    svSponsorAddress: 'http://sv-app.sv-1:5014',
    onboardingSecret,
    persistenceConfig: {
      host: validatorPostgres.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: validatorPostgres.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
    },
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    extraDependsOn,
    auth0AppName: 'validator1',
    participantBootstrapDump,
    participantAddress: 'participant',
    topupConfig,
    svValidator: false,
    additionalJvmOptions: jmxOptions(),
  });
}
