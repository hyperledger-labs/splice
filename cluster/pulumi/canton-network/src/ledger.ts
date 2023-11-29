import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Service } from '@pulumi/kubernetes/core/v1';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

import { jmxOptions } from '../../common/src/jmx';
import { Postgres } from './postgres';

export function installDomain(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres
): pulumi.Resource {
  const sanitizedNs = xns.logicalName.replace('-', '_');
  const sanitizedName = name.replace('-', '_');

  const mediatorDbName = `${sanitizedNs}_${sanitizedName}_mediator`;
  const mediatorDb = postgres.createDatabase(mediatorDbName);

  const sequencerDbName = `${sanitizedNs}_${sanitizedName}_sequencer`;
  const sequencerDb = postgres.createDatabase(sequencerDbName);

  return installCNHelmChart(
    xns,
    name,
    'cn-domain',
    {
      postgres: postgres.address,
      postgresMediatorDb: mediatorDbName,
      postgresSequencerDb: sequencerDbName,
      additionalJvmOptions: jmxOptions(),
    },
    [mediatorDb, sequencerDb]
  );
}

export function installGlobalDomain(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres,
  sequencer: PostgresSequencer | CometBftSequencer
): pulumi.Resource {
  const sanitizedNs = xns.logicalName.replace('-', '_');
  const sanitizedName = name.replace('-', '_');

  const mediatorDbName = `${sanitizedNs}_${sanitizedName}_mediator`;
  const mediatorDb = postgres.createDatabase(mediatorDbName);

  const sequencerDbName = `${sanitizedNs}_${sanitizedName}_sequencer`;
  const sequencerDb = postgres.createDatabase(sequencerDbName);

  return installCNHelmChart(
    xns,
    name,
    'cn-global-domain',
    {
      postgres: postgres.address,
      postgresMediatorDb: mediatorDbName,
      postgresSequencerDb: sequencerDbName,
      sequencerDriver:
        sequencer.driver === 'cometbft'
          ? {
              type: sequencer.driver,
              host: pulumi.interpolate`${sequencer.service.metadata.name}.${sequencer.service.metadata.namespace}.svc.cluster.local`,
              port: 26657,
            }
          : {
              type: sequencer.driver,
              address: sequencer.postgres.address,
            },
      metrics: {
        enable: true,
      },
      additionalJvmOptions: jmxOptions(),
    },
    [mediatorDb, sequencerDb]
  );
}

export function installParticipant(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres,
  participantAdminUserNameFrom: k8s.types.input.core.v1.EnvVarSource,
  disableAutoInit = false,
  isDevNet: boolean,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource {
  const sanitizedNs = xns.logicalName.replace('-', '_');
  const postgresDbName = `${sanitizedNs}_participant`;

  const postgresDb = postgres.createDatabase(postgresDbName);
  return installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      postgres: postgres.address,
      postgresDb: postgresDbName,
      postgresSchema: postgresDbName,
      participantAdminUserNameFrom,
      disableAutoInit,
      metrics: {
        enable: true,
      },
      additionalJvmOptions: jmxOptions(),
    },
    dependsOn.concat([postgresDb])
  );
}

type PostgresSequencer = {
  driver: 'postgres';
  postgres: Postgres;
};

type CometBftSequencer = {
  driver: 'cometbft';
  service: Service;
};
