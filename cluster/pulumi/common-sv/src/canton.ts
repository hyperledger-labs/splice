import { Release } from '@pulumi/kubernetes/helm/v3';
import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  config,
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  ExactNamespace,
  SpliceCustomResourceOptions,
} from 'cn-pulumi-common';
import { CnChartVersion } from 'cn-pulumi-common/src/artifacts';
import { Postgres } from 'cn-pulumi-common/src/postgres';

import { DecentralizedSynchronizerNode, StaticCometBftConfigWithNodeName } from './index';
import { installSvParticipant } from './participant';

export function installCantonComponents(
  xns: ExactNamespace,
  migrationId: DomainMigrationIndex,
  auth0Client: Auth0Client,
  svConfig: {
    onboardingName: string;
    isFirstSv: boolean;
    isCoreSv: boolean;
  },
  dbs: {
    participant: Postgres;
    mediator: Postgres;
    sequencer: Postgres;
  },
  version: CnChartVersion,
  migrationConfig: DecentralizedSynchronizerMigrationConfig,
  cometbft: {
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    };
    sv1SvApp?: Release;
  },
  opts?: SpliceCustomResourceOptions
): { decentralizedSynchronizer: DecentralizedSynchronizerNode; participant: Release } {
  const logLevel = config.envFlag('SPLICE_DEPLOYMENT_NO_SV_DEBUG')
    ? 'INFO'
    : config.envFlag('SPLICE_DEPLOYMENT_SINGLE_SV_DEBUG')
      ? svConfig.isFirstSv
        ? 'DEBUG'
        : 'INFO'
      : 'DEBUG';

  const isActiveMigration = migrationConfig.active.migrationId === migrationId;
  const auth0Config = auth0Client.getCfg();
  const participant = installSvParticipant(
    xns,
    migrationId,
    auth0Config,
    isActiveMigration,
    dbs.participant,
    logLevel,
    version,
    svConfig.onboardingName,
    auth0UserNameEnvVarSource('sv'),
    opts
  );
  const decentralizedSynchronizerNode = new DecentralizedSynchronizerNode(
    migrationId,
    xns,
    {
      sequencerPostgres: dbs.sequencer,
      mediatorPostgres: dbs.mediator,
      setCoreDbNames: svConfig.isCoreSv,
    },
    cometbft,
    isActiveMigration,
    migrationConfig.isRunningMigration(),
    svConfig.onboardingName,
    logLevel,
    version
  );
  return {
    decentralizedSynchronizer: decentralizedSynchronizerNode,
    participant: participant,
  };
}
