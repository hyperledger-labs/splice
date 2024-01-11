import * as pulumi from '@pulumi/pulumi';
import { Service } from '@pulumi/kubernetes/core/v1';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource } from '@pulumi/pulumi';
import { CLUSTER_BASENAME, ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import { installCometBftNode } from './cometbft';
import { initDatabase, Postgres } from './postgres';

export type GlobalDomainUpgradeConfig = {
  legacyGlobalDomainId?: DomainIndex;
  activeGlobalDomainId?: DomainIndex;
  upgradeGlobalDomainId?: DomainIndex;
};

export const DefaultGlobalDomainId = 0;

export function installDomainSpecificComponent<T extends pulumi.Resource>(
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  defaultComponent: (id: DomainIndex) => T,
  component: (id: DomainIndex) => T
): T {
  if (globalDomainUpgradeConfig.activeGlobalDomainId == undefined) {
    return defaultComponent(DefaultGlobalDomainId);
  } else {
    const activeComponent = component(globalDomainUpgradeConfig.activeGlobalDomainId);
    if (globalDomainUpgradeConfig.legacyGlobalDomainId) {
      component(globalDomainUpgradeConfig.legacyGlobalDomainId);
    }
    if (globalDomainUpgradeConfig.upgradeGlobalDomainId) {
      component(globalDomainUpgradeConfig.upgradeGlobalDomainId);
    }
    return activeComponent;
  }
}

export function installGlobalDomain(
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  xns: ExactNamespace,
  sequencerPostgres: Postgres,
  mediatorPostgres: Postgres,
  cometbft: {
    name: string;
    onboardingName: string;
    syncSource?: Release;
  }
): GlobalDomainNode {
  return installDomainSpecificComponent(
    globalDomainUpgradeConfig,
    defaultId =>
      new GlobalDomainNode(defaultId, xns, sequencerPostgres, mediatorPostgres, cometbft, false),
    (id: DomainIndex) =>
      new GlobalDomainNode(id, xns, sequencerPostgres, mediatorPostgres, cometbft, true)
  );
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
    disableAutoInit: boolean
  ) {
    super('canton:network:domain:global', `${xns.logicalName}-global-domain-${domainId}`);
    this.id = domainId;
    this.cometbft = cometbft;
    this.name = 'global-domain-' + domainId.toString();

    const sanitizedName = this.name.replaceAll('-', '_');

    const mediatorDbName = `${sanitizedName}_mediator`;
    const mediatorDb = mediatorPostgres.createDatabaseAndInstallMetrics(mediatorDbName, {
      parent: this,
    });

    const sequencerDbName = `${sanitizedName}_sequencer`;
    const sequencerDb = sequencerPostgres.createDatabaseAndInstallMetrics(sequencerDbName, {
      parent: this,
    });
    const cometBftService = installCometBftNode(
      xns,
      cometbft.name,
      cometbft.onboardingName,
      domainId,
      cometbft.syncSource,
      { parent: this }
    );

    this.cometbftRpcService = cometBftService;

    const initDb = initDatabase();

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
        init: initDb && { initDb },
      },
      { dependsOn: [mediatorDb, sequencerDb, cometBftService], parent: this }
    );
    installCNHelmChart(
      xns,
      'ingress-sequencer-' + this.name,
      'cn-cluster-ingress-runbook',
      {
        withSvIngress: true,
        ingress: {
          wallet: false,
          cns: false,
          scan: false,
          sequencer: true,
          sv: true,
          globalDomain: {
            globalDomainId: domainId.toString(),
          },
        },
        cluster: {
          hostname: `${CLUSTER_BASENAME}.network.canton.global`,
          svNamespace: xns.logicalName,
        },
      },
      { dependsOn: [xns.ns, domainNodeRelease], parent: this }
    );
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
