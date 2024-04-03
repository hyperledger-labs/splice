import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource } from '@pulumi/pulumi';
import {
  ExactNamespace,
  installCNHelmChart,
  DomainMigrationIndex,
  jmxOptions,
  defaultVersion,
  CnChartVersion,
  sequencerResources,
} from 'cn-pulumi-common';

import { installCometBftNode } from './cometbft';
import { Postgres, installPostgresMetrics } from './postgres';
import { StaticCometBftConfigWithNodeName } from './svConfigs';

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
        founder: StaticCometBftConfigWithNodeName;
        peers: StaticCometBftConfigWithNodeName[];
      };
      syncSource?: Release;
    },
    disableAutoInit: boolean,
    active: boolean,
    nodeIdentifier: string,
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
      cometbft.name,
      cometbft.onboardingName,
      cometbft.nodeConfigs,
      domainMigrationId,
      active,
      version,
      cometbft.syncSource,
      { parent: this }
    );

    this.cometbftRpcServiceName = cometbftRelease.rpcServiceName;

    const synchronizerNodeRelease = installCNHelmChart(
      xns,
      this.name,
      'cn-global-domain',
      {
        sequencer: {
          persistence: {
            databaseName: sequencerDbName,
            secretName: sequencerPostgres.secretName,
            host: sequencerPostgres.address,
          },
          driver: {
            type: 'cometbft',
            host: pulumi.interpolate`${this.cometbftRpcServiceName}.${xns.logicalName}.svc.cluster.local`,
            port: 26657,
          },
          ...sequencerResources,
        },
        mediator: {
          persistence: {
            databaseName: mediatorDbName,
            secretName: mediatorPostgres.secretName,
            host: mediatorPostgres.address,
          },
        },
        metrics: {
          enable: true,
        },
        additionalJvmOptions: jmxOptions(),
        disableAutoInit: disableAutoInit,
        nodeIdentifier,
      },
      version,
      { dependsOn: [cometbftRelease.release], parent: this }
    );

    installPostgresMetrics(mediatorPostgres, mediatorDbName, [synchronizerNodeRelease]);
    installPostgresMetrics(sequencerPostgres, sequencerDbName, [synchronizerNodeRelease]);
  }

  get namespaceInternalSequencerAddress(): string {
    return `${this.name}-sequencer`;
  }

  get namespaceInternalMediatorAddress(): string {
    return `${this.name}-mediator`;
  }

  get founderInternalSequencerAddress(): string {
    return `http://${this.namespaceInternalSequencerAddress}.sv-1:5008`;
  }
}
