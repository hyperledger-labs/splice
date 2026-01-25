// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  ExactNamespace,
  installLedgerApiUserSecret,
  SpliceCustomResourceOptions,
  withAddedDependencies,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  InstalledMigrationSpecificSv,
  SingleSvConfiguration,
  StaticCometBftConfigWithNodeName,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { installPostgres, Postgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import {
  InStackCantonBftDecentralizedSynchronizerNode,
  InStackCometBftDecentralizedSynchronizerNode,
} from '@lfdecentralizedtrust/splice-pulumi-sv-canton/src/decentralizedSynchronizerNode';

import { spliceConfig } from '../../common/src/config/config';
import { installSvParticipant } from './participant';

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
  } & SingleSvConfiguration,
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
  const isActiveMigration = migrationConfig.active.id === migrationId;

  const auth0Config = auth0Client.getCfg();
  const ledgerApiUserSecret = installLedgerApiUserSecret(
    auth0Client,
    xns,
    `sv-canton-migration-${migrationId}`,
    'sv'
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
      svConfig.participant?.cloudSql || spliceConfig.pulumiProjectConfig.cloudSql,
      true,
      { isActive: migrationStillRunning, migrationId, disableProtection }
    );
  const mediatorPostgres =
    dbs?.mediator ||
    installPostgres(
      xns,
      `mediator-${migrationId}-pg`,
      `mediator-pg`,
      migrationInfo.version,
      svConfig.mediator?.cloudSql || spliceConfig.pulumiProjectConfig.cloudSql,
      true,
      {
        isActive: migrationStillRunning,
        migrationId,
        disableProtection,
      }
    );
  const sequencerPostgres =
    dbs?.sequencer ||
    installPostgres(
      xns,
      `sequencer-${migrationId}-pg`,
      `sequencer-pg`,
      migrationInfo.version,
      svConfig.sequencer?.cloudSql || spliceConfig.pulumiProjectConfig.cloudSql,
      true,
      { isActive: migrationStillRunning, migrationId, disableProtection }
    );
  if (migrationStillRunning) {
    const participant = installSvParticipant(
      xns,
      svConfig,
      migrationId,
      auth0Config,
      participantPg,
      migrationInfo.version,
      ledgerApiUserSecretSource,
      imagePullServiceAccountName,
      withAddedDependencies(opts, ledgerApiUserSecret ? [ledgerApiUserSecret] : [])
    );
    const decentralizedSynchronizerNode = migrationInfo.sequencer.enableBftSequencer
      ? new InStackCantonBftDecentralizedSynchronizerNode(
          svConfig,
          migrationId,
          svConfig.ingressName,
          xns,
          {
            sequencerPostgres: sequencerPostgres,
            mediatorPostgres: mediatorPostgres,
            setCoreDbNames: svConfig.isCoreSv,
          },
          migrationInfo.version,
          imagePullServiceAccountName,
          opts
        )
      : new InStackCometBftDecentralizedSynchronizerNode(
          svConfig,
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
          migrationInfo.version,
          imagePullServiceAccountName,
          disableProtection,
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
