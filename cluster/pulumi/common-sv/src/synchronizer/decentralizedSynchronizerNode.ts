import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource, Resource } from '@pulumi/pulumi';
import {
  ChartValues,
  domainLivenessProbeInitialDelaySeconds,
  DomainMigrationIndex,
  ExactNamespace,
  installSpliceHelmChart,
  jmxOptions,
  loadYamlFromFile,
  LogLevel,
  SPLICE_ROOT,
  sanitizedForPostgres,
  sequencerResources,
  sequencerTokenExpirationTime,
  SpliceCustomResourceOptions,
} from 'splice-pulumi-common';
import { CnChartVersion } from 'splice-pulumi-common/src/artifacts';
import { spliceConfig } from 'splice-pulumi-common/src/config/config';
import { Postgres } from 'splice-pulumi-common/src/postgres';

import { CometBftNodeConfigs } from './cometBftNodeConfigs';
import { installCometBftNode } from './cometbft';
import { StaticCometBftConfigWithNodeName } from './cometbftConfig';

export interface DecentralizedSynchronizerNode {
  migrationId: number;
  cometbftRpcServiceName: string;
  readonly namespaceInternalSequencerAddress: string;
  readonly namespaceInternalMediatorAddress: string;
  readonly sv1InternalSequencerAddress: string;
  readonly dependencies: pulumi.Resource[];
}

export class CrossStackDecentralizedSynchronizerNode implements DecentralizedSynchronizerNode {
  name: string;
  migrationId: number;
  cometbftRpcServiceName: string;

  constructor(migrationId: DomainMigrationIndex, cometbftNodeIdentifier: string) {
    this.migrationId = migrationId;
    this.name = 'global-domain-' + migrationId.toString();
    this.cometbftRpcServiceName = `${cometbftNodeIdentifier}-cometbft-rpc`;
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

  readonly dependencies: Resource[] = [];
}

export class InStackDecentralizedSynchronizerNode
  extends ComponentResource
  implements DecentralizedSynchronizerNode
{
  migrationId: number;
  name: string;
  cometbft: {
    onboardingName: string;
    syncSource?: Release;
  };
  cometbftRpcServiceName: string;
  version: CnChartVersion;
  readonly dependencies: Resource[] = [this];

  constructor(
    migrationId: DomainMigrationIndex,
    xns: ExactNamespace,
    dbs: {
      setCoreDbNames: boolean;
      sequencerPostgres: Postgres;
      mediatorPostgres: Postgres;
    },
    cometbft: {
      nodeConfigs: {
        self: StaticCometBftConfigWithNodeName;
        sv1: StaticCometBftConfigWithNodeName;
        peers: StaticCometBftConfigWithNodeName[];
      };
      enableStateSync?: boolean;
      enableTimeoutCommit?: boolean;
    },
    active: boolean,
    runningMigration: boolean,
    onboardingName: string,
    logLevel: LogLevel,
    version: CnChartVersion,
    imagePullServiceAccountName?: string,
    opts?: SpliceCustomResourceOptions
  ) {
    super('canton:network:domain:global', `${xns.logicalName}-global-domain-${migrationId}`);
    this.migrationId = migrationId;
    this.cometbft = { ...cometbft, onboardingName };
    this.name = 'global-domain-' + migrationId.toString();
    const sanitizedName = sanitizedForPostgres(this.name);
    const mediatorDbName = `${sanitizedName}_mediator`;
    const sequencerDbName = `${sanitizedName}_sequencer`;
    this.version = version;

    const cometbftRelease = installCometBftNode(
      xns,
      this.cometbft.onboardingName,
      new CometBftNodeConfigs(migrationId, cometbft.nodeConfigs),
      migrationId,
      active,
      runningMigration,
      logLevel.toLowerCase(),
      version,
      cometbft.enableStateSync,
      cometbft.enableTimeoutCommit,
      imagePullServiceAccountName,
      {
        ...opts,
        parent: this,
      }
    );

    this.cometbftRpcServiceName = cometbftRelease.rpcServiceName;

    const decentralizedSynchronizerValues: ChartValues = loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
      {
        MIGRATION_ID: migrationId.toString(),
      }
    );

    installSpliceHelmChart(
      xns,
      this.name,
      'splice-global-domain',
      {
        ...decentralizedSynchronizerValues,
        ...{
          logLevel: logLevel,
          sequencer: {
            ...decentralizedSynchronizerValues.sequencer,
            persistence: {
              ...decentralizedSynchronizerValues.sequencer.persistence,
              secretName: dbs.sequencerPostgres.secretName,
              host: dbs.sequencerPostgres.address,
              postgresName: dbs.sequencerPostgres.instanceName,
              ...(dbs.setCoreDbNames ? { databaseName: sequencerDbName } : {}),
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
            ...decentralizedSynchronizerValues.mediator,
            persistence: {
              ...decentralizedSynchronizerValues.mediator.persistence,
              secretName: dbs.mediatorPostgres.secretName,
              host: dbs.mediatorPostgres.address,
              postgresName: dbs.mediatorPostgres.instanceName,
              ...(dbs.setCoreDbNames ? { databaseName: mediatorDbName } : {}),
            },
          },
          enablePostgresMetrics: true,
          metrics: {
            enable: true,
            migration: {
              id: migrationId,
            },
          },
          livenessProbeInitialDelaySeconds: domainLivenessProbeInitialDelaySeconds,
          additionalJvmOptions: jmxOptions(),
          pvc: spliceConfig.configuration.persistentSequencerHeapDumps
            ? {
                size: '10Gi',
                volumeStorageClass: 'standard-rwo',
              }
            : undefined,
          serviceAccountName: imagePullServiceAccountName,
        },
      },
      version,
      {
        ...opts,
        dependsOn: (opts?.dependsOn || []).concat([
          cometbftRelease.release,
          dbs.sequencerPostgres,
          dbs.mediatorPostgres,
        ]),
        parent: this,
      }
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
