import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ComponentResource } from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import { installCometBftNode } from './cometbft';
import { Postgres } from './postgres';

export class GlobalDomainNode extends ComponentResource {
  name: string;
  cometbft: {
    name: string;
    onboardingName: string;
    syncSource?: Release;
  };

  constructor(
    xns: ExactNamespace,
    name: string,
    sequencerPostgres: Postgres,
    mediatorPostgres: Postgres,
    cometbft: {
      name: string;
      onboardingName: string;
      syncSource?: Release;
    }
  ) {
    const logicalName = xns.logicalName + '-' + name;
    super('canton:network:domain:global', logicalName);
    this.name = name;
    this.cometbft = cometbft;

    const sanitizedName = name.replace('-', '_');

    const mediatorDbName = `${sanitizedName}_mediator`;
    const mediatorDb = mediatorPostgres.createDatabase(mediatorDbName);

    const sequencerDbName = `${sanitizedName}_sequencer`;
    const sequencerDb = sequencerPostgres.createDatabase(sequencerDbName);
    const cometBftService = installCometBftNode(
      xns,
      cometbft.name,
      cometbft.onboardingName,
      cometbft.syncSource,
      { parent: this }
    );
    installCNHelmChart(
      xns,
      name,
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
      },
      { dependsOn: [mediatorDb, sequencerDb, cometBftService], parent: this }
    );
  }
}
