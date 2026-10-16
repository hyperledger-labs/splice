// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@canton-network/splice-pulumi-common/src/postgres';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BucketConfig,
  BootstrappingDumpConfig,
  CLUSTER_HOSTNAME,
  activeVersion,
  ExactNamespace,
  exactNamespace,
  installAuth0UISecret,
  installSpliceHelmChart,
  spliceInstanceNames,
  imagePullSecret,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ValidatorTopupConfig,
  ansDomainPrefix,
} from '@canton-network/splice-pulumi-common';
import { installLoopback } from '@canton-network/splice-pulumi-common-sv';
import {
  installParticipant,
  installWalletGateway,
  splitwellDarPaths,
} from '@canton-network/splice-pulumi-common-validator';
import {
  AutoAcceptTransfersConfig,
  installValidatorApp,
} from '@canton-network/splice-pulumi-common-validator/src/validator';

import { spliceConfig } from '../../common/src/config/config';
import { validator1Config } from './config';

export async function installValidator1(
  auth0Client: Auth0Client,
  name: string,
  onboardingSecret: string,
  validatorWalletUser: string,
  splitPostgresInstances: boolean,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  installSplitwell: boolean,
  backupConfig?: BucketConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig,
  autoAcceptTransfers?: AutoAcceptTransfersConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace(name, true);

  const loopback = installLoopback(xns);

  const participantPruningConfig = validator1Config?.participantPruningSchedule;

  const additionalEnvVars = [
    ...(validator1Config?.validatorApp?.additionalEnvVars || []),
    {
      name: 'ADDITIONAL_CONFIG_AUTO_ONBOARDING_TRAFFIC_PURCHASE',
      value:
        'canton.validator-apps.validator_backend.enable-internal-auto-onboarding-through-sv-traffic-faucet = true',
    },
  ];

  const imagePullDeps = imagePullSecret(xns);

  const defaultPostgres = !splitPostgresInstances
    ? await postgres.installPostgres(
        xns,
        'postgres',
        'postgres',
        activeVersion,
        spliceConfig.pulumiProjectConfig.cloudSql,
        spliceConfig.pulumiProjectConfig.defaultSplicePostgresConfig,
        false
      )
    : undefined;

  const validatorPostgres =
    defaultPostgres ||
    (await postgres.installPostgres(
      xns,
      `validator-pg`,
      `validator-pg`,
      activeVersion,
      spliceConfig.pulumiProjectConfig.cloudSql,
      spliceConfig.pulumiProjectConfig.defaultSplicePostgresConfig,
      true
    ));
  const validatorDbName = `validator1`;

  const participantDependsOn: CnInput<pulumi.Resource>[] = imagePullDeps.concat(loopback);

  const participant = await installParticipant(
    validator1Config,
    xns,
    auth0Client.getCfg(),
    validator1Config?.disableAuth,
    decentralizedSynchronizerMigrationConfig.active.version,
    defaultPostgres,
    {
      dependsOn: participantDependsOn,
    }
  );

  const extraDependsOn: CnInput<pulumi.Resource>[] = participantDependsOn.concat([
    validatorPostgres,
  ]);
  const scanAddress = `http://scan-app.sv-1:5012`;

  const validator = await installValidatorApp({
    validatorWalletUsers: pulumi.output([validatorWalletUser]),
    xns,
    dependencies: [],
    appDars: splitwellDarPaths,
    validatorPartyHint: `digitalasset-${name}-1`,
    svSponsorAddress: `http://sv-app.sv-1:5014`,
    onboardingSecret,
    persistenceConfig: {
      host: validatorPostgres.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: validatorPostgres.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
      postgresName: validatorPostgres.instanceName,
    },
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    extraDependsOn,
    participantBootstrapDump,
    participantAddress: participant.participantAddress,
    topupConfig,
    svValidator: false,
    scanAddress,
    auth0Client: auth0Client,
    auth0ValidatorAppName: 'validator1',
    autoAcceptTransfers: autoAcceptTransfers,
    nodeIdentifier: 'validator1',
    participantPruningConfig,
    deduplicationDuration: validator1Config?.deduplicationDuration,
    disableAuth: validator1Config?.disableAuth,
    version: activeVersion,
    additionalEnvVars: additionalEnvVars,
  });
  const walletGatewayConfig = validator1Config.walletGateway;
  if (walletGatewayConfig.enabled) {
    await installWalletGateway(
      auth0Client,
      xns,
      walletGatewayConfig,
      participant.participantAddress,
      validatorPostgres,
      validator1Config?.logging?.level,
      [validator]
    );
  }

  installIngress(xns, installSplitwell, walletGatewayConfig.enabled);

  if (installSplitwell) {
    installSpliceHelmChart(
      xns,
      'splitwell-web-ui',
      'splice-splitwell-web-ui',
      {
        ...spliceInstanceNames,
        auth: {
          audience: 'https://canton.network.global',
        },
        clusterUrl: CLUSTER_HOSTNAME,
      },
      activeVersion,
      {
        dependsOn: imagePullDeps.concat([
          await installAuth0UISecret(auth0Client, xns, 'splitwell'),
        ]),
      }
    );
  }

  return validator;
}

function installIngress(xns: ExactNamespace, splitwell: boolean, walletGateway: boolean) {
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
        splitwell: splitwell,
        walletGateway: walletGateway,
        decentralizedSynchronizer: {},
      },
    }
  );
}
