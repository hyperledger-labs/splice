// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  config,
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  ExactNamespace,
  installLedgerApiUserSecret,
  SpliceCustomResourceOptions,
  withAddedDependencies,
} from 'splice-pulumi-common';
import {
  InstalledMigrationSpecificSv,
  installSvParticipant,
  StaticCometBftConfigWithNodeName,
} from 'splice-pulumi-common-sv';
import { installPostgres, Postgres } from 'splice-pulumi-common/src/postgres';
import {
  InStackCantonBftDecentralizedSynchronizerNode,
  InStackCometBftDecentralizedSynchronizerNode,
} from 'sv-canton-pulumi-deployment/src/decentralizedSynchronizerNode';

export function installCantonComponents(
  xns: ExactNamespace,
  migrationId: DomainMigrationIndex,
  auth0Client: Auth0Client,
  svConfig: {
    onboardingName: string;
    ingressName: string;
    auth0SvAppName: string;
    isFirstSv: boolean;
    isCoreSv: boolean;
  },
  migrationConfig: DecentralizedSynchronizerMigrationConfig,
  cometbft: {
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    };
    enableStateSync?: boolean;
    enableTimeoutCommit?: boolean;
  },
  dbs?: {
    participant: Postgres;
    mediator: Postgres;
    sequencer: Postgres;
  },
  opts?: SpliceCustomResourceOptions,
  disableProtection?: boolean,
  imagePullServiceAccountName?: string
): InstalledMigrationSpecificSv | undefined {
  const logLevel = config.envFlag('SPLICE_DEPLOYMENT_NO_SV_DEBUG')
    ? 'INFO'
    : config.envFlag('SPLICE_DEPLOYMENT_SINGLE_SV_DEBUG')
      ? svConfig.isFirstSv
        ? 'DEBUG'
        : 'INFO'
      : 'DEBUG';

  const isActiveMigration = migrationConfig.active.id === migrationId;

  const auth0Config = auth0Client.getCfg();
  const ledgerApiUserSecret = installLedgerApiUserSecret(
    auth0Client,
    xns,
    `sv-canton-migration-${migrationId}`,
    svConfig.auth0SvAppName
  );
  const ledgerApiUserSecretSource = auth0UserNameEnvVarSource(
    `sv-canton-migration-${migrationId}`,
    true
  );

  const migrationStillRunning = migrationConfig.isStillRunning(migrationId);
  const migrationInfo = migrationConfig.allMigrations.find(
    migration => migration.id === migrationId
  );
  if (!migrationInfo) {
    throw new Error(`Migration ${migrationId} not found in migration config`);
  }
  const participantPg =
    dbs?.participant ||
    installPostgres(
      xns,
      `participant-${migrationId}-pg`,
      `participant-pg`,
      migrationInfo.version,
      true,
      { isActive: migrationStillRunning, migrationId, disableProtection }
    );
  const mediatorPostgres =
    dbs?.mediator ||
    installPostgres(xns, `mediator-${migrationId}-pg`, `mediator-pg`, migrationInfo.version, true, {
      isActive: migrationStillRunning,
      migrationId,
      disableProtection,
    });
  const sequencerPostgres =
    dbs?.sequencer ||
    installPostgres(
      xns,
      `sequencer-${migrationId}-pg`,
      `sequencer-pg`,
      migrationInfo.version,
      true,
      { isActive: migrationStillRunning, migrationId, disableProtection }
    );
  if (migrationStillRunning) {
    const participant = installSvParticipant(
      xns,
      migrationId,
      auth0Config,
      isActiveMigration,
      participantPg,
      logLevel,
      migrationInfo.version,
      svConfig.onboardingName,
      ledgerApiUserSecretSource,
      imagePullServiceAccountName,
      withAddedDependencies(opts, ledgerApiUserSecret ? [ledgerApiUserSecret] : [])
    );
    const decentralizedSynchronizerNode = migrationInfo.sequencer.enableBftSequencer
      ? new InStackCantonBftDecentralizedSynchronizerNode(
          migrationId,
          svConfig.ingressName,
          xns,
          {
            sequencerPostgres: sequencerPostgres,
            mediatorPostgres: mediatorPostgres,
            setCoreDbNames: svConfig.isCoreSv,
          },
          isActiveMigration,
          logLevel,
          migrationInfo.version,
          imagePullServiceAccountName,
          opts
        )
      : new InStackCometBftDecentralizedSynchronizerNode(
          cometbft,
          migrationId,
          xns,
          {
            sequencerPostgres: sequencerPostgres,
            mediatorPostgres: mediatorPostgres,
            setCoreDbNames: svConfig.isCoreSv,
          },
          isActiveMigration,
          migrationConfig.isRunningMigration(),
          svConfig.onboardingName,
          logLevel,
          migrationInfo.version,
          imagePullServiceAccountName,
          opts
        );
    return {
      decentralizedSynchronizer: decentralizedSynchronizerNode,
      participant: {
        asDependencies: [participant],
        internalClusterAddress: participant.name,
      },
    };
  } else {
    return undefined;
  }
}
