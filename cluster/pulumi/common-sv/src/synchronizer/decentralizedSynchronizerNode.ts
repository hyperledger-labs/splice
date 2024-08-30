import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource, Resource } from '@pulumi/pulumi';
import { StaticCometBftConfigWithNodeName } from 'canton-network-pulumi-deployment/src/svConfigs';
import {
  autoInitValues,
  defaultVersion,
  DomainMigrationIndex,
  ExactNamespace,
  installSpliceHelmChart,
  jmxOptions,
  LogLevel,
  sequencerResources,
  sequencerTokenExpirationTime,
} from 'cn-pulumi-common';
import { CnChartVersion } from 'cn-pulumi-common/src/artifacts';
import { Postgres } from 'cn-pulumi-common/src/postgres';

import { CometBftNodeConfigs } from './cometBftNodeConfigs';
import { installCometBftNode } from './cometbft';

export class DecentralizedSynchronizerNode extends ComponentResource {
  migrationId: number;
  name: string;
  cometbft: {
    name: string;
    onboardingName: string;
    syncSource?: Release;
  };
  cometbftRpcServiceName: string;
  active: boolean;
  version: CnChartVersion;

  constructor(
    domainMigrationId: DomainMigrationIndex,
    xns: ExactNamespace,
    sequencerPostgres: Postgres,
    mediatorPostgres: Postgres,
    cometbft: {
      name: string;
      onboardingName: string;
      nodeConfigs: {
        self: StaticCometBftConfigWithNodeName;
        sv1: StaticCometBftConfigWithNodeName;
        peers: StaticCometBftConfigWithNodeName[];
      };
      sv1SvApp?: Resource;
    },
    active: boolean,
    runningMigration: boolean,
    nodeIdentifier: string,
    logLevel: LogLevel,
    version: CnChartVersion = defaultVersion
  ) {
    super('canton:network:domain:global', `${xns.logicalName}-global-domain-${domainMigrationId}`);
    this.migrationId = domainMigrationId;
    this.cometbft = cometbft;
    this.name = 'global-domain-' + domainMigrationId.toString();
    this.active = active;
    this.version = version;

    const sanitizedName = this.name.replaceAll('-', '_');
    const mediatorDbName = `${sanitizedName}_mediator`;
    const sequencerDbName = `${sanitizedName}_sequencer`;
    const cometbftRelease = installCometBftNode(
      xns,
      cometbft.onboardingName,
      new CometBftNodeConfigs(domainMigrationId, cometbft.nodeConfigs),
      domainMigrationId,
      active,
      runningMigration,
      logLevel.toLowerCase(),
      version,
      cometbft.sv1SvApp,
      { parent: this }
    );

    this.cometbftRpcServiceName = cometbftRelease.rpcServiceName;

    installSpliceHelmChart(
      xns,
      this.name,
      'cn-global-domain',
      {
        logLevel: logLevel,
        sequencer: {
          persistence: {
            databaseName: sequencerDbName,
            secretName: sequencerPostgres.secretName,
            host: sequencerPostgres.address,
            postgresName: sequencerPostgres.instanceName,
          },
          driver: {
            type: 'cometbft',
            host: pulumi.interpolate`${this.cometbftRpcServiceName}.${xns.logicalName}.svc.cluster.local`,
            port: 26657,
          },
          tokenExpirationTime: sequencerTokenExpirationTime,
          ...sequencerResources,
        },
        mediator: {
          persistence: {
            databaseName: mediatorDbName,
            secretName: mediatorPostgres.secretName,
            host: mediatorPostgres.address,
            postgresName: mediatorPostgres.instanceName,
          },
        },
        metrics: {
          enable: true,
          migration: {
            id: domainMigrationId,
            active: active,
          },
        },
        ...autoInitValues('cn-global-domain', version, nodeIdentifier),
        additionalJvmOptions: jmxOptions(),
        enablePostgresMetrics: true,
      },
      version,
      { dependsOn: [cometbftRelease.release, sequencerPostgres, mediatorPostgres], parent: this }
    );
  }

  get namespaceInternalSequencerAddress(): string {
    return `${this.name}-sequencer`;
  }

  get namespaceInternalMediatorAddress(): string {
    return `${this.name}-mediator`;
  }

  get sv1InternalSequencerAddress(): string {
    return `http://${this.namespaceInternalSequencerAddress}.sv-1:5008`;
  }
}
