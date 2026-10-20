// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  ExactNamespace,
  installLedgerApiUserSecret,
  SpliceCustomResourceOptions,
} from '@canton-network/splice-pulumi-common';
import {
  InstalledMigrationSpecificSv,
  SingleSvConfiguration,
  StaticCometBftConfigWithNodeName,
} from '@canton-network/splice-pulumi-common-sv';
import { installPostgres, Postgres } from '@canton-network/splice-pulumi-common/src/postgres';
import {
  InStackCantonBftDecentralizedSynchronizerNode,
  InStackCometBftDecentralizedSynchronizerNode,
} from '@canton-network/splice-pulumi-sv-canton/src/decentralizedSynchronizerNode';

export async function installCantonComponents(
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
): Promise<InstalledMigrationSpecificSv | undefined> {
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
  const physicalSynchronizerConfig = svConfig.physicalSynchronizers[migrationId];
  const version = isActiveMigration
    ? (svConfig.versionOverride ?? migrationInfo.version)
    : migrationInfo.version;
  const mediatorPostgres =
    dbs?.mediator ||
    (await installPostgres(
      xns,
      `mediator-${migrationId}-pg`,
      `mediator-pg`,
      version,
      physicalSynchronizerConfig.mediator.cloudSql,
      physicalSynchronizerConfig.mediator.splicePostgres,
      true,
      {
        isActive: migrationStillRunning,
        migrationId,
        disableProtection,
      }
    ));
  const sequencerPostgres =
    dbs?.sequencer ||
    (await installPostgres(
      xns,
      `sequencer-${migrationId}-pg`,
      `sequencer-pg`,
      version,
      physicalSynchronizerConfig.sequencer.cloudSql,
      physicalSynchronizerConfig.sequencer.splicePostgres,
      true,
      { isActive: migrationStillRunning, migrationId, disableProtection }
    ));
  const bftSequencerPostgres =
    migrationInfo.sequencer.enableBftSequencer && migrationInfo.sequencer.dedicatedBftSequencerDb
      ? await installPostgres(
          xns,
          `sequencer-bft-${migrationId}-pg`,
          `sequencer-bft-pg`,
          version,
          physicalSynchronizerConfig.cantonbft.cloudSql,
          physicalSynchronizerConfig.cantonbft.splicePostgres,
          true,
          { isActive: migrationStillRunning, migrationId, disableProtection }
        )
      : undefined;
  if (migrationStillRunning) {
    const decentralizedSynchronizerNode = migrationInfo.sequencer.enableBftSequencer
      ? new InStackCantonBftDecentralizedSynchronizerNode(
          svConfig,
          migrationId,
          svConfig.ingressName,
          xns,
          {
            sequencerPostgres: sequencerPostgres,
            mediatorPostgres: mediatorPostgres,
            bftSequencerPostgres: bftSequencerPostgres,
            setCoreDbNames: svConfig.isCoreSv,
          },
          version,
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
          svConfig.onboardingName,
          version,
          imagePullServiceAccountName,
          disableProtection,
          migrationInfo.cometbft?.volumeSize,
          opts
        );
    return {
      decentralizedSynchronizer: decentralizedSynchronizerNode,
    };
  } else {
    return undefined;
  }
}
