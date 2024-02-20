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
  DomainMigrationIndex,
} from 'cn-pulumi-common';
import type { Auth0Client, ExactNamespace } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installParticipant } from './ledger';
import { installPostgresMetrics } from './postgres';
import { installValidatorApp } from './validator';

export async function installValidator1(
  auth0Client: Auth0Client,
  name: string,
  onboardingSecret: string,
  validatorWalletUser: string,
  splitPostgresInstances: boolean,
  svActiveMigrationId: DomainMigrationIndex,
  dependsOn: pulumi.Resource[],
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

  installIngress(xns);
  const validatorPostgres = splitPostgresInstances
    ? postgres.installPostgres(xns, 'validator-pg', true)
    : participantPostgres;

  const validatorDbName = 'validator1';

  const extraDependsOn: pulumi.Resource[] = dependsOn.concat([
    participantPostgres,
    validatorPostgres,
  ]);
  const globalDomainUrl = `https://sequencer.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`;
  const scanAddress = `http://scan-app-${svActiveMigrationId}.sv-1:5012`;

  const validator = installValidatorApp({
    validatorWalletUser,
    xns,
    participant,
    // We vet both versions to easily test upgrades.
    appDars: [
      'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar',
      'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.2.0.dar',
    ],
    validatorPartyHint: `${name}_validator_service_user`,
    extraDomains: [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
    svSponsorAddress: `http://sv-app-${svActiveMigrationId}.sv-1:5014`,
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
    participantBootstrapDump,
    participantAddress: 'participant',
    topupConfig,
    svValidator: false,
    globalDomainUrl,
    scanAddress,
    secrets: {
      xns,
      auth0Client,
      auth0AppName: 'validator1',
    },
  });

  installPostgresMetrics(validatorPostgres, validatorDbName, [validator]);

  return validator;
}

function installIngress(xns: ExactNamespace) {
  installCNHelmChart(
    xns,
    'cluster-ingress-validator1',
    'cn-cluster-ingress-runbook',
    {
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        hostPrefix: '',
        svNamespace: xns.logicalName,
      },
      withSvIngress: false,
      ingress: {
        splitwell: true,
      },
    },
    {}
  );
}
