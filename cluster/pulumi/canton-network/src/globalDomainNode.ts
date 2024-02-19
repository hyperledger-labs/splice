import * as pulumi from '@pulumi/pulumi';
import { Service } from '@pulumi/kubernetes/core/v1';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource } from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import { installCometBftNode } from './cometbft';
import { Postgres, installPostgresMetrics } from './postgres';
import { StaticCometBftConfigWithNodeName } from './svconfs';

export class GlobalDomainUpgradeConfig {
  prepareUpgrade: boolean;
  legacyMigrationId?: DomainMigrationIndex;
  activeMigrationId: DomainMigrationIndex;
  upgradeMigrationId?: DomainMigrationIndex;

  constructor(
    prepareUpgrade: boolean,
    activeMigrationId: DomainMigrationIndex,
    legacyMigrationId?: DomainMigrationIndex,
    upgradeMigrationId?: DomainMigrationIndex
  ) {
    this.prepareUpgrade = prepareUpgrade;
    this.legacyMigrationId = legacyMigrationId;
    this.activeMigrationId = activeMigrationId;
    this.upgradeMigrationId = upgradeMigrationId;
  }

  static fromEnv(): GlobalDomainUpgradeConfig {
    return new GlobalDomainUpgradeConfig(
      process.env.GLOBAL_DOMAIN_PREPARE_UPGRADE === 'true',
      processIndex(process.env.GLOBAL_DOMAIN_ACTIVE_MIGRATION_ID) || DefaultMigrationId,
      processIndex(process.env.GLOBAL_DOMAIN_LEGACY_MIGRATION_ID),
      processIndex(process.env.GLOBAL_DOMAIN_UPGRADE_MIGRATION_ID)
    );
  }

  containsUpgrade(): boolean {
    return this.upgradeMigrationId != undefined;
  }

  isDefaultActive(): boolean {
    return this.activeMigrationId == DefaultMigrationId;
  }
}

function processIndex(maybeValue?: string) {
  if (maybeValue == undefined) {
    return undefined;
  }
  const index = Number(maybeValue);
  if (index >= 0 && index < 10) {
    return index as DomainMigrationIndex;
  } else {
    throw new Error(`Cannot process ${maybeValue} as domain index`);
  }
}

export const DefaultMigrationId = 0;

export function installDomainSpecificComponent<T>(
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  component: (migrationId: DomainMigrationIndex, isActive: boolean) => T
): T {
  if (globalDomainUpgradeConfig.upgradeMigrationId) {
    component(globalDomainUpgradeConfig.upgradeMigrationId, false);
  }
  if (globalDomainUpgradeConfig.legacyMigrationId) {
    component(globalDomainUpgradeConfig.legacyMigrationId, false);
  }
  if (globalDomainUpgradeConfig.activeMigrationId == DefaultMigrationId) {
    return component(DefaultMigrationId, true);
  } else {
    return component(globalDomainUpgradeConfig.activeMigrationId, true);
  }
}

export type DomainMigrationIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9;

export class GlobalDomainNode extends ComponentResource {
  migrationId: number;
  name: string;
  cometbft: {
    name: string;
    onboardingName: string;
    syncSource?: Release;
  };
  cometbftRpcService: Service;
  active: boolean;

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
    active: boolean
  ) {
    super('canton:network:domain:global', `${xns.logicalName}-global-domain-${domainMigrationId}`);
    this.migrationId = domainMigrationId;
    this.cometbft = cometbft;
    this.name = 'global-domain-' + domainMigrationId.toString();
    this.active = active;

    const sanitizedName = this.name.replaceAll('-', '_');
    const mediatorDbName = `${sanitizedName}_mediator`;
    const sequencerDbName = `${sanitizedName}_sequencer`;

    const cometBftService = installCometBftNode(
      xns,
      cometbft.name,
      cometbft.onboardingName,
      cometbft.nodeConfigs,
      domainMigrationId,
      cometbft.syncSource,
      { parent: this }
    );

    this.cometbftRpcService = cometBftService;

    const domainNodeRelease = installCNHelmChart(
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
            host: pulumi.interpolate`${cometBftService.metadata.name}.${cometBftService.metadata.namespace}.svc.cluster.local`,
            port: 26657,
          },
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
      },
      { dependsOn: [cometBftService], parent: this }
    );

    installPostgresMetrics(mediatorPostgres, mediatorDbName, [domainNodeRelease]);
    installPostgresMetrics(sequencerPostgres, sequencerDbName, [domainNodeRelease]);
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
