import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource, Resource } from '@pulumi/pulumi';
import {
  autoInitValues,
  ChartValues,
  defaultVersion,
  DomainMigrationIndex,
  ExactNamespace,
  installSpliceHelmChart,
  jmxOptions,
  loadYamlFromFile,
  LogLevel,
  REPO_ROOT,
  sanitizedForPostgres,
  sequencerResources,
  sequencerTokenExpirationTime,
  SpliceCustomResourceOptions,
} from 'cn-pulumi-common';
import { CnChartVersion } from 'cn-pulumi-common/src/artifacts';
import { Postgres } from 'cn-pulumi-common/src/postgres';

import { CometBftNodeConfigs } from './cometBftNodeConfigs';
import { installCometBftNode } from './cometbft';
import { StaticCometBftConfigWithNodeName } from './cometbftConfig';

export class DecentralizedSynchronizerNode extends ComponentResource {
  migrationId: number;
  name: string;
  cometbft: {
    onboardingName: string;
    syncSource?: Release;
  };
  cometbftRpcServiceName: string;
  active: boolean;
  version: CnChartVersion;

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
      sv1SvApp?: Resource;
    },
    active: boolean,
    runningMigration: boolean,
    onboardingName: string,
    logLevel: LogLevel,
    version: CnChartVersion = defaultVersion,
    opts?: SpliceCustomResourceOptions
  ) {
    super('canton:network:domain:global', `${xns.logicalName}-global-domain-${migrationId}`);
    this.migrationId = migrationId;
    this.cometbft = { ...cometbft, onboardingName };
    this.name = 'global-domain-' + migrationId.toString();
    const sanitizedName = sanitizedForPostgres(this.name);
    const mediatorDbName = `${sanitizedName}_mediator`;
    const sequencerDbName = `${sanitizedName}_sequencer`;
    this.active = active;
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
      cometbft.sv1SvApp,
      {
        ...opts,
        parent: this,
      }
    );

    this.cometbftRpcServiceName = cometbftRelease.rpcServiceName;

    const decentralizedSynchronizerValues: ChartValues = loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
      {
        MIGRATION_ID: migrationId.toString(),
      }
    );

    installSpliceHelmChart(
      xns,
      this.name,
      'cn-global-domain',
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
          ...autoInitValues('cn-global-domain', version, onboardingName),
          enablePostgresMetrics: true,
          metrics: {
            enable: true,
            migration: {
              id: migrationId,
              active: this.active,
            },
          },
          additionalJvmOptions: jmxOptions(),
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
        // TODO(#14507) - remove alias once latest release is 0.2.0
        aliases: [
          {
            name: `global-domain-${migrationId}`,
            parent: undefined,
          },
        ],
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
