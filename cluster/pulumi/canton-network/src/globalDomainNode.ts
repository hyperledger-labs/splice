import * as pulumi from '@pulumi/pulumi';
import { Service } from '@pulumi/kubernetes/core/v1';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource } from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import { installCometBftNode } from './cometbft';
import { Postgres, installPostgresMetrics } from './postgres';

export type GlobalDomainUpgradeConfig = {
  prepareUpgrade: boolean;
  legacyGlobalDomainId?: DomainIndex;
  activeGlobalDomainId: DomainIndex;
  upgradeGlobalDomainId?: DomainIndex;
};

export const DefaultGlobalDomainId = 0;

export function installDomainSpecificComponent<T>(
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  component: (id: DomainIndex, isActive: boolean) => T
): T {
  if (globalDomainUpgradeConfig.upgradeGlobalDomainId) {
    component(globalDomainUpgradeConfig.upgradeGlobalDomainId, false);
  }
  if (globalDomainUpgradeConfig.legacyGlobalDomainId) {
    component(globalDomainUpgradeConfig.legacyGlobalDomainId, false);
  }
  if (globalDomainUpgradeConfig.activeGlobalDomainId == DefaultGlobalDomainId) {
    return component(DefaultGlobalDomainId, true);
  } else {
    return component(globalDomainUpgradeConfig.activeGlobalDomainId, true);
  }
}

export type DomainIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9;

export class GlobalDomainNode extends ComponentResource {
  id: number;
  name: string;
  cometbft: {
    name: string;
    onboardingName: string;
    syncSource?: Release;
  };
  cometbftRpcService: Service;
  active: boolean;

  constructor(
    domainId: DomainIndex,
    xns: ExactNamespace,
    sequencerPostgres: Postgres,
    mediatorPostgres: Postgres,
    cometbft: {
      name: string;
      onboardingName: string;
      syncSource?: Release;
    },
    disableAutoInit: boolean,
    active: boolean
  ) {
    super('canton:network:domain:global', `${xns.logicalName}-global-domain-${domainId}`);
    this.id = domainId;
    this.cometbft = cometbft;
    this.name = 'global-domain-' + domainId.toString();
    this.active = active;

    const sanitizedName = this.name.replaceAll('-', '_');
    const mediatorDbName = `${sanitizedName}_mediator`;
    const sequencerDbName = `${sanitizedName}_sequencer`;

    const cometBftService = installCometBftNode(
      xns,
      cometbft.name,
      cometbft.onboardingName,
      domainId,
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
