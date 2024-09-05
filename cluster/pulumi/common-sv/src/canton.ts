import { Release } from '@pulumi/kubernetes/helm/v3';
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
    sv1SvApp?: Release;
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

  const isActiveMigration = migrationConfig.active.migrationId === migrationId;
  const auth0Config = auth0Client.getCfg();
  const participantPg =
    dbs?.participant ||
    installPostgres(xns, `participant-${migrationId}-pg`, `participant-pg`, true);
  const mediatorPostgres =
    dbs?.mediator || installPostgres(xns, `mediator-${migrationId}-pg`, `mediator-pg`, true);
  const sequencerPostgres =
    dbs?.sequencer || installPostgres(xns, `sequencer-${migrationId}-pg`, `sequencer-pg`, true);
  if (migrationConfig.isStillRunning(migrationId)) {
    const participant = installSvParticipant(
      xns,
      migrationId,
      auth0Config,
      isActiveMigration,
      participantPg,
      logLevel,
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
      logLevel
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
