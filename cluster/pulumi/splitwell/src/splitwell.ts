// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  ansDomainPrefix,
  Auth0Client,
  auth0UserNameEnvVar,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_HOSTNAME,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  exactNamespace,
  ExactNamespace,
  failOnAppVersionMismatch,
  imagePullSecret,
  installLedgerApiSecret,
  installSpliceHelmChart,
  ValidatorTopupConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { installLoopback } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import {
  installParticipant,
  splitwellDarPaths,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { installValidatorApp } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validator';

import { spliceConfig } from '../../common/src/config/config';
import { splitwellConfig } from '../../common/src/config/splitwellConfig';

export async function installSplitwell(
  auth0Client: Auth0Client,
  providerWalletUser: string,
  validatorWalletUser: string,
  onboardingSecret: string,
  splitPostgresInstances: boolean,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace('splitwell', true);
  const sharedPostgres = splitPostgresInstances
    ? undefined
    : postgres.installPostgres(
        xns,
        'splitwell-pg',
        'splitwell-pg',
        activeVersion,
        spliceConfig.pulumiProjectConfig.cloudSql,
        splitPostgresInstances
      );

  const loopback = installLoopback(xns);

  const imagePullDeps = imagePullSecret(xns);

  installIngress(xns, imagePullDeps);

  const participant = installParticipant(
    splitwellConfig,
    decentralizedSynchronizerMigrationConfig.active.id,
    xns,
    auth0Client.getCfg(),
    false,
    decentralizedSynchronizerMigrationConfig.active.version,
    sharedPostgres,
    {
      dependsOn: imagePullDeps.concat(loopback),
    }
  );

  const swPostgres =
    sharedPostgres ||
    postgres.installPostgres(
      xns,
      'sw-pg',
      'sw-pg',
      activeVersion,
      spliceConfig.pulumiProjectConfig.cloudSql,
      true
    );
  const splitwellDbName = 'app_splitwell';

  const scanAddress = `http://scan-app.sv-1:5012`;
  installSpliceHelmChart(
    xns,
    'splitwell-app',
    'splice-splitwell-app',
    {
      postgres: swPostgres.address,
      metrics: {
        enable: true,
      },
      migration: {
        id: decentralizedSynchronizerMigrationConfig.active.id,
      },
      scanAddress: scanAddress,
      participantHost: participant.participantAddress,
      persistence: {
        host: swPostgres.address,
        databaseName: pulumi.Output.create(splitwellDbName),
        secretName: swPostgres.secretName,
        schema: pulumi.Output.create(splitwellDbName),
        user: pulumi.Output.create('cnadmin'),
        port: pulumi.Output.create(5432),
      },
      failOnAppVersionMismatch: failOnAppVersionMismatch,
      maxDarVersion: splitwellConfig?.maxDarVersion,
      logLevel: splitwellConfig.logging?.level,
      logAsyncFlush: splitwellConfig.logging?.async,
    },
    activeVersion,
    { dependsOn: imagePullDeps }
  );

  const validatorPostgres =
    sharedPostgres ||
    postgres.installPostgres(
      xns,
      'validator-pg',
      'validator-pg',
      activeVersion,
      spliceConfig.pulumiProjectConfig.cloudSql,
      true
    );
  const validatorDbName = 'val_splitwell';

  const extraDependsOn = imagePullDeps.concat(
    await installLedgerApiSecret(auth0Client, xns, 'splitwell')
  );

  const participantPruningConfig = splitwellConfig?.participantPruningSchedule;

  return await installValidatorApp({
    xns,
    extraDependsOn,
    dependencies: [],
    ...decentralizedSynchronizerMigrationConfig.migratingNodeConfig(),
    additionalUsers: [
      auth0UserNameEnvVar('splitwell'),
      { name: 'CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME', value: providerWalletUser },
    ],
    additionalConfig: [
      'canton.validator-apps.validator_backend.app-instances.splitwell = {',
      '  service-user = ${?SPLICE_APP_SPLITWELL_LEDGER_API_AUTH_USER_NAME}',
      '  wallet-user = ${?CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME}',
      // We vet all versions to easily test upgrades.
      `  dars = ["${splitwellDarPaths.join('", "')}"]`,
      '}',
    ].join('\n'),
    onboardingSecret,
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    svSponsorAddress: `http://sv-app.sv-1:5014`,
    participantBootstrapDump,
    participantAddress: participant.participantAddress,
    topupConfig: topupConfig,
    svValidator: false,
    participantPruningConfig: participantPruningConfig,
    persistenceConfig: {
      host: validatorPostgres.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: validatorPostgres.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
      postgresName: validatorPostgres.instanceName,
    },
    scanAddress: scanAddress,
    auth0Client: auth0Client,
    auth0ValidatorAppName: 'splitwell_validator',
    validatorWalletUsers: pulumi.output([validatorWalletUser]),
    validatorPartyHint: 'digitalasset-splitwell-1',
    nodeIdentifier: 'splitwell',
    logLevel: splitwellConfig.logging?.level,
    logAsync: splitwellConfig.logging?.async,
  });
}

function installIngress(xns: ExactNamespace, dependsOn: CnInput<pulumi.Resource>[]) {
  installSpliceHelmChart(xns, 'cluster-ingress-splitwell-uis', 'splice-cluster-ingress-runbook', {
    cluster: {
      hostname: CLUSTER_HOSTNAME,
      svNamespace: xns.logicalName,
    },
    spliceDomainNames: {
      nameServiceDomain: ansDomainPrefix,
    },
    withSvIngress: false,
    opts: {
      dependsOn: dependsOn,
    },
  });
}
