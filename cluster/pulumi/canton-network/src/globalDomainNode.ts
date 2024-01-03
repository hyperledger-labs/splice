import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource } from '@pulumi/pulumi';
import { CLUSTER_BASENAME, ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import { installCometBftNode } from './cometbft';
import { initDatabase, Postgres } from './postgres';

export class GlobalDomainNode extends ComponentResource {
  id: string;
  name: string;
  cometbft: {
    name: string;
    onboardingName: string;
    syncSource?: Release;
  };

  constructor(
    domainId: string,
    xns: ExactNamespace,
    sequencerPostgres: Postgres,
    mediatorPostgres: Postgres,
    cometbft: {
      name: string;
      onboardingName: string;
      syncSource?: Release;
    }
  ) {
    super('canton:network:domain:global', `${xns.logicalName}-global-domain-${domainId}`);
    this.id = domainId;
    this.cometbft = cometbft;
    this.name = 'global-domain-' + domainId;

    const sanitizedName = this.name.replaceAll('-', '_');

    const mediatorDbName = `${sanitizedName}_mediator`;
    const mediatorDb = mediatorPostgres.createDatabaseAndInstallMetrics(mediatorDbName);

    const sequencerDbName = `${sanitizedName}_sequencer`;
    const sequencerDb = sequencerPostgres.createDatabaseAndInstallMetrics(sequencerDbName);
    const cometBftService = installCometBftNode(
      xns,
      cometbft.name,
      cometbft.onboardingName,
      this.name,
      cometbft.syncSource,
      { parent: this }
    );

    const initDb = initDatabase();

    installCNHelmChart(
      xns,
      this.name,
      'cn-global-domain',
      {
        sequencerPostgres: sequencerPostgres.address,
        sequencerPostgresSecretName: sequencerPostgres.secretName,
        mediatorPostgres: mediatorPostgres.address,
        mediatorPostgresSecretName: mediatorPostgres.secretName,
        postgresMediatorDb: mediatorDbName,
        postgresSequencerDb: sequencerDbName,
        sequencerDriver: {
          type: 'cometbft',
          host: pulumi.interpolate`${cometBftService.metadata.name}.${cometBftService.metadata.namespace}.svc.cluster.local`,
          port: 26657,
        },
        metrics: {
          enable: true,
        },
        additionalJvmOptions: jmxOptions(),
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
          sv: false,
          cns: false,
          scan: false,
          sequencer: {
            globalDomain: domainId,
          },
        },
        cluster: {
          hostname: `${CLUSTER_BASENAME}.network.canton.global`,
          svNamespace: xns.logicalName,
        },
      },
      { dependsOn: [xns.ns], parent: this }
    );
  }
}
