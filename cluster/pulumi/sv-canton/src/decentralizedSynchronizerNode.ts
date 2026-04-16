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
  getPathToPrivateConfigFile,
  installSpliceHelmChart,
  loadJsonFromFile,
  loadYamlFromFile,
  LogLevel,
  sanitizedForPostgres,
  sequencerTokenExpirationTime,
  SPLICE_ROOT,
  SpliceCustomResourceOptions,
  standardStorageClassName,
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

const getSequencerRateLimitConfig = (): string | undefined => {
  const rateLimitsFile = getPathToPrivateConfigFile('sequencer-rate-limits.json');
  if (!rateLimitsFile) {
    return undefined;
  }
  const rateLimits = loadJsonFromFile(rateLimitsFile);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const limitsForMessageType = (message: string, config: any): string =>
    [
      `canton.sequencers.sequencer.sequencer.block.throughput-cap.messages.${message} {`,
      `  global-tps-cap = ${config.globalTpsCap}`,
      `  per-client-tps-cap = ${config.perClientTpsCap}`,
      `  global-kbps-cap = ${config.globalKbpsCap}`,
      `  per-client-kbps-cap = ${config.perClientKbpsCap}`,
      '}',
    ].join('\n');
  return [
    limitsForMessageType('confirmation-request', rateLimits.messages.confirmationRequest),
    limitsForMessageType('topology', rateLimits.messages.topology),
  ].join('\n');
};

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
    apiRequestLogLevel?: LogLevel,
    logAsyncFlush?: boolean,
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

    const rateLimitConfig = getSequencerRateLimitConfig();

    installSpliceHelmChart(
      this.xns,
      this.name,
      'splice-global-domain',
      {
        ...decentralizedSynchronizerValues,
        ...{
          logLevel: logLevel,
          logLevelStdout: logLevelStdout,
          apiRequestLogLevel: apiRequestLogLevel,
          logAsyncFlush: logAsyncFlush,
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
            additionalEnvVars: (rateLimitConfig
              ? [{ name: 'ADDITIONAL_CONFIG_SEQUENCER_RATE_LIMITS', value: rateLimitConfig }]
              : []
            ).concat(svConfig.sequencer?.additionalEnvVars || []),
            resources: svConfig.sequencer?.resources,
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
            resources: svConfig.mediator?.resources,
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
                volumeStorageClass: standardStorageClassName,
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
      {
        type: 'cometbft',
        host: pulumi.interpolate`${cometbftRelease.rpcServiceName}.${xns.logicalName}.svc.cluster.local`,
        port: 26657,
      },
      version,
      svConfig.logging?.cantonLogLevel,
      svConfig.logging?.cantonStdoutLogLevel,
      svConfig.logging?.apiRequestLogLevel,
      svConfig.logging?.cantonAsync,
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
    version: CnChartVersion,
    imagePullServiceAccountName?: string,
    opts?: SpliceCustomResourceOptions
  ) {
    super(migrationId, xns, version);
    this.installDecentralizedSynchronizer(
      svConfig,
      dbs,
      {
        type: 'cantonbft',
        externalAddress: `sequencer-p2p-${migrationId}.${ingressName}.${CLUSTER_HOSTNAME}`,
        externalPort: 443,
      },
      version,
      svConfig.logging?.cantonLogLevel,
      svConfig.logging?.cantonStdoutLogLevel,
      svConfig.logging?.apiRequestLogLevel,
      svConfig.logging?.cantonAsync,
      imagePullServiceAccountName,
      opts
    );
  }
}
