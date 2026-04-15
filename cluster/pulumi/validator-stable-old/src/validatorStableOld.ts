// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  ansDomainPrefix,
  Auth0Client,
  CLUSTER_HOSTNAME,
  CnChartVersion,
  DecentralizedSynchronizerMigrationConfig,
  exactNamespace,
  ExactNamespace,
  imagePullSecret,
  installLedgerApiSecret,
  installSpliceHelmChart,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { installLoopback } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { installParticipant } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { installValidatorApp } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validator';

import { spliceConfig } from '../../common/src/config/config';
import { validatorStableOldConfig } from './config';

export async function installValidatorStableOld(
  auth0Client: Auth0Client,
  validatorWalletUser: string,
  onboardingSecret: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace('validator-stable-old', true);

  const loopback = installLoopback(xns);

  const imagePullDeps = imagePullSecret(xns);

  const validatorVersion = CnChartVersion.parse('0.5.1');

  installIngress(xns, decentralizedSynchronizerMigrationConfig);

  const validatorPostgres = postgres.installPostgres(
    xns,
    'postgres',
    'postgres',
    validatorVersion,
    spliceConfig.pulumiProjectConfig.cloudSql,
    false
  );

  const participant = installParticipant(
    validatorStableOldConfig,
    decentralizedSynchronizerMigrationConfig.activeMigrationId,
    xns,
    auth0Client.getCfg(),
    false,
    validatorVersion,
    validatorPostgres,
    {
      dependsOn: imagePullDeps.concat(loopback),
    }
  );

  const scanAddress = `http://scan-app.sv-1:5012`;

  const validatorDbName = 'validator_stable_old';

  const extraDependsOn = imagePullDeps;

  const participantPruningConfig = validatorStableOldConfig?.participantPruningSchedule;

  return await installValidatorApp({
    xns,
    extraDependsOn,
    dependencies: [],
    ...decentralizedSynchronizerMigrationConfig.migratingNodeConfig(),
    additionalConfig: undefined,
    onboardingSecret,
    backupConfig: undefined,
    svSponsorAddress: `http://sv-app.sv-1:5014`,
    participantAddress: participant.participantAddress,
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
    auth0ValidatorAppName: 'validator-stable-old',
    validatorWalletUsers: pulumi.output([validatorWalletUser]),
    validatorPartyHint: 'digitalasset-validator-1',
    nodeIdentifier: 'validator-stable-old',
    logLevel: validatorStableOldConfig.logging?.level,
    logAsync: validatorStableOldConfig.logging?.async,
    version: validatorVersion,
  });
}

function installIngress(
  xns: ExactNamespace,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig
) {
  installSpliceHelmChart(
    xns,
    `cluster-ingress-${xns.logicalName}`,
    'splice-cluster-ingress-runbook',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        svNamespace: xns.logicalName,
      },
      withSvIngress: false,
      spliceDomainNames: {
        nameServiceDomain: ansDomainPrefix,
      },
      ingress: {
        splitwell: false,
        decentralizedSynchronizer: {
          activeMigrationId: decentralizedSynchronizerMigrationConfig.activeMigrationId.toString(),
        },
      },
    }
  );
}
