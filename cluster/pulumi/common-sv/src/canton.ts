import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  config,
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  ExactNamespace,
  SpliceCustomResourceOptions,
} from 'splice-pulumi-common';
import { installPostgres, Postgres } from 'splice-pulumi-common/src/postgres';

import {
  DecentralizedSynchronizerNode,
  InStackDecentralizedSynchronizerNode,
  StaticCometBftConfigWithNodeName,
  SvParticipant,
} from './index';
import { installSvParticipant } from './participant';

export type InstalledMigrationSpecificSv = {
  decentralizedSynchronizer: DecentralizedSynchronizerNode;
  participant: SvParticipant;
};

export function installCantonComponents(
  xns: ExactNamespace,
  migrationId: DomainMigrationIndex,
  auth0Client: Auth0Client,
  svConfig: {
    onboardingName: string;
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
  opts?: SpliceCustomResourceOptions
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
  const migrationStillRunning = migrationConfig.isStillRunning(migrationId);
  const participantPg =
    dbs?.participant ||
    installPostgres(
      xns,
      `participant-${migrationId}-pg`,
      `participant-pg`,
      true,
      migrationStillRunning
    );
  const mediatorPostgres =
    dbs?.mediator ||
    installPostgres(xns, `mediator-${migrationId}-pg`, `mediator-pg`, true, migrationStillRunning);
  const sequencerPostgres =
    dbs?.sequencer ||
    installPostgres(
      xns,
      `sequencer-${migrationId}-pg`,
      `sequencer-pg`,
      true,
      migrationStillRunning
    );
  if (migrationStillRunning) {
    const migrationInfo = migrationConfig
      .runningMigrations()
      .find(migration => migration.id === migrationId);
    if (!migrationInfo) {
      throw new Error(`Migration ${migrationId} not found in migration config`);
    }
    const participant = installSvParticipant(
      xns,
      migrationId,
      auth0Config,
      isActiveMigration,
      participantPg,
      logLevel,
      migrationInfo.version,
      svConfig.onboardingName,
      auth0UserNameEnvVarSource('sv'),
      opts
    );
    const decentralizedSynchronizerNode = new InStackDecentralizedSynchronizerNode(
      migrationId,
      xns,
      {
        sequencerPostgres: sequencerPostgres,
        mediatorPostgres: mediatorPostgres,
        setCoreDbNames: svConfig.isCoreSv,
      },
      cometbft,
      isActiveMigration,
      migrationConfig.isRunningMigration(),
      svConfig.onboardingName,
      logLevel,
      migrationInfo.version,
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
