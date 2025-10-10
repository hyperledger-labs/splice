// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  ChartValues,
  CLUSTER_HOSTNAME,
  CnChartVersion,
  domainLivenessProbeInitialDelaySeconds,
  DomainMigrationIndex,
  ExactNamespace,
  getAdditionalJvmOptions,
  installSpliceHelmChart,
  loadYamlFromFile,
  LogLevel,
  sanitizedForPostgres,
  sequencerResources,
  sequencerTokenExpirationTime,
  SPLICE_ROOT,
  SpliceCustomResourceOptions,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  CometBftNodeConfigs,
  CometbftSynchronizerNode,
  DecentralizedSynchronizerNode,
  installCometBftNode,
  SingleSvConfiguration,
  StaticCometBftConfigWithNodeName,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { spliceConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { Postgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource, Output, Resource } from '@pulumi/pulumi';

abstract class InStackDecentralizedSynchronizerNode
  extends ComponentResource
  implements DecentralizedSynchronizerNode
{
  xns: ExactNamespace;
  migrationId: number;
  name: string;
  version: CnChartVersion;

  readonly dependencies: Resource[] = [this];

  protected constructor(
    migrationId: DomainMigrationIndex,
    xns: ExactNamespace,
    version: CnChartVersion
  ) {
    super('canton:network:domain:global', `${xns.logicalName}-global-domain-${migrationId}`);
    this.xns = xns;
    this.migrationId = migrationId;
    this.name = 'global-domain-' + migrationId.toString();
    this.version = version;
  }

  protected installDecentralizedSynchronizer(
    svConfig: SingleSvConfiguration,
    dbs: {
      setCoreDbNames: boolean;
      sequencerPostgres: Postgres;
      mediatorPostgres: Postgres;
    },
    active: boolean,
    driver:
      | { type: 'cometbft'; host: Output<string>; port: number }
      | {
          type: 'cantonbft';
          externalAddress: string;
          externalPort: number;
        },
    version: CnChartVersion,
    logLevel?: LogLevel,
    logLevelStdout?: LogLevel,
    imagePullServiceAccountName?: string,
    opts?: SpliceCustomResourceOptions
  ) {
    const sanitizedName = sanitizedForPostgres(this.name);
    const mediatorDbName = `${sanitizedName}_mediator`;
    const sequencerDbName = `${sanitizedName}_sequencer`;
    this.version = version;

    const decentralizedSynchronizerValues: ChartValues = loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
      {
        MIGRATION_ID: this.migrationId.toString(),
      }
    );

    installSpliceHelmChart(
      this.xns,
      this.name,
      'splice-global-domain',
      {
        ...decentralizedSynchronizerValues,
        ...{
          logLevel: logLevel,
          logLevelStdout: logLevelStdout,
          sequencer: {
            ...decentralizedSynchronizerValues.sequencer,
            persistence: {
              ...decentralizedSynchronizerValues.sequencer.persistence,
              secretName: dbs.sequencerPostgres.secretName,
              host: dbs.sequencerPostgres.address,
              postgresName: dbs.sequencerPostgres.instanceName,
              ...(dbs.setCoreDbNames ? { databaseName: sequencerDbName } : {}),
            },
            driver: driver,
            tokenExpirationTime: sequencerTokenExpirationTime,
            additionalEnvVars: svConfig.sequencer?.additionalEnvVars,
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
            additionalEnvVars: svConfig.mediator?.additionalEnvVars,
          },
          enablePostgresMetrics: true,
          metrics: {
            enable: true,
            migration: {
              id: this.migrationId,
            },
          },
          livenessProbeInitialDelaySeconds: domainLivenessProbeInitialDelaySeconds,
          additionalJvmOptions: getAdditionalJvmOptions(svConfig.sequencer?.additionalJvmOptions),
          pvc: spliceConfig.configuration.persistentHeapDumps
            ? {
                size: '10Gi',
                volumeStorageClass: 'standard-rwo',
              }
            : undefined,
          serviceAccountName: imagePullServiceAccountName,
        },
      },
      this.version,
      {
        ...opts,
        dependsOn: (opts?.dependsOn || []).concat([dbs.sequencerPostgres, dbs.mediatorPostgres]),
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

export class InStackCometBftDecentralizedSynchronizerNode
  extends InStackDecentralizedSynchronizerNode
  implements CometbftSynchronizerNode
{
  cometbft: {
    onboardingName: string;
    syncSource?: Release;
  };
  cometbftRpcServiceName: string;

  constructor(
    svConfig: SingleSvConfiguration,
    cometbft: {
      nodeConfigs: {
        self: StaticCometBftConfigWithNodeName;
        sv1: StaticCometBftConfigWithNodeName;
        peers: StaticCometBftConfigWithNodeName[];
      };
      enableStateSync?: boolean;
      enableTimeoutCommit?: boolean;
    },
    migrationId: DomainMigrationIndex,
    xns: ExactNamespace,
    dbs: {
      setCoreDbNames: boolean;
      sequencerPostgres: Postgres;
      mediatorPostgres: Postgres;
    },
    active: boolean,
    runningMigration: boolean,
    onboardingName: string,
    version: CnChartVersion,
    imagePullServiceAccountName?: string,
    disableProtection?: boolean,
    opts?: SpliceCustomResourceOptions
  ) {
    super(migrationId, xns, version);
    const cometbftRelease = installCometBftNode(
      xns,
      onboardingName,
      new CometBftNodeConfigs(migrationId, cometbft.nodeConfigs),
      svConfig,
      migrationId,
      active,
      runningMigration,
      version,
      cometbft.enableStateSync,
      cometbft.enableTimeoutCommit,
      imagePullServiceAccountName,
      disableProtection,
      {
        ...opts,
        parent: this,
      }
    );

    this.cometbft = { ...cometbft, onboardingName };
    this.cometbftRpcServiceName = cometbftRelease.rpcServiceName;
    this.installDecentralizedSynchronizer(
      svConfig,
      dbs,
      active,
      {
        type: 'cometbft',
        host: pulumi.interpolate`${cometbftRelease.rpcServiceName}.${xns.logicalName}.svc.cluster.local`,
        port: 26657,
      },
      version,
      svConfig.logging?.cantonLogLevel,
      svConfig.logging?.cantonStdoutLogLevel,
      imagePullServiceAccountName,
      opts
    );
  }
}

export class InStackCantonBftDecentralizedSynchronizerNode extends InStackDecentralizedSynchronizerNode {
  constructor(
    svConfig: SingleSvConfiguration,
    migrationId: DomainMigrationIndex,
    ingressName: string,
    xns: ExactNamespace,
    dbs: {
      setCoreDbNames: boolean;
      sequencerPostgres: Postgres;
      mediatorPostgres: Postgres;
    },
    active: boolean,
    version: CnChartVersion,
    imagePullServiceAccountName?: string,
    opts?: SpliceCustomResourceOptions
  ) {
    super(migrationId, xns, version);
    this.installDecentralizedSynchronizer(
      svConfig,
      dbs,
      active,
      {
        type: 'cantonbft',
        externalAddress: `sequencer-p2p-${migrationId}.${ingressName}.${CLUSTER_HOSTNAME}`,
        externalPort: 443,
      },
      version,
      svConfig.logging?.cantonLogLevel,
      svConfig.logging?.cantonStdoutLogLevel,
      imagePullServiceAccountName,
      opts
    );
  }
}
