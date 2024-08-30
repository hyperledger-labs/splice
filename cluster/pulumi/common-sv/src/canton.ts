import { Release } from '@pulumi/kubernetes/helm/v3';
import { SvConfig } from 'canton-network-pulumi-deployment/src/sv';
import {
  auth0UserNameEnvVarSource,
  config,
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  ExactNamespace,
} from 'cn-pulumi-common';
import { CnChartVersion } from 'cn-pulumi-common/src/artifacts';
import { Postgres } from 'cn-pulumi-common/src/postgres';

import { DecentralizedSynchronizerNode, StaticCometBftConfigWithNodeName } from './index';
import { installSvParticipant } from './participant';

export function installCantonComponents(
  xns: ExactNamespace,
  migrationId: DomainMigrationIndex,
  svConfig: SvConfig,
  dbs: {
    participant: Postgres;
    mediator: Postgres;
    sequencer: Postgres;
  },
  version: CnChartVersion,
  migrationConfig: DecentralizedSynchronizerMigrationConfig,
  cometbft: {
    name: string;
    onboardingName: string;
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    };
    sv1SvApp?: Release;
  }
): { decentralizedSynchronizer: DecentralizedSynchronizerNode; participant: Release } {
  const logLevel =
    config.envFlag('SPLICE_DEPLOYMENT_NO_SV_DEBUG') ||
    (config.envFlag('SPLICE_DEPLOYMENT_SINGLE_SV_DEBUG') && !svConfig.isFirstSv)
      ? 'INFO'
      : 'DEBUG';

  const isActiveMigration = migrationConfig.active.migrationId === migrationId;
  const auth0Config = svConfig.auth0Client.getCfg();
  const participant = installSvParticipant(
    xns,
    migrationId,
    auth0Config,
    isActiveMigration,
    dbs.participant,
    logLevel,
    version,
    svConfig.onboardingName,
    auth0UserNameEnvVarSource('sv')
  );
  const decentralizedSynchronizerNode = new DecentralizedSynchronizerNode(
    migrationId,
    xns,
    dbs.sequencer,
    dbs.mediator,
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
