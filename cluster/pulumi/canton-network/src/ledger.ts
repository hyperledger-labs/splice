import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

import { jmxOptions } from '../../common/src/jmx';
import { initDatabase, Postgres } from './postgres';

export function installDomain(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres
): pulumi.Resource {
  const sanitizedName = name.replaceAll('-', '_');

  const mediatorDbName = `${sanitizedName}_mediator`;
  const mediatorDb = postgres.createDatabaseAndInstallMetrics(mediatorDbName);

  const sequencerDbName = `${sanitizedName}_sequencer`;
  const sequencerDb = postgres.createDatabaseAndInstallMetrics(sequencerDbName);

  const initDb = initDatabase();

  return installCNHelmChart(
    xns,
    name,
    'cn-domain',
    {
      postgres: postgres.address,
      postgresMediatorDb: mediatorDbName,
      postgresSequencerDb: sequencerDbName,
      additionalJvmOptions: jmxOptions(),
      init: initDb && { initDb },
    },
    {
      dependsOn: [mediatorDb, sequencerDb],
    }
  );
}

export function installParticipant(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres,
  participantAdminUserNameFrom: k8s.types.input.core.v1.EnvVarSource,
  disableAutoInit = false,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource {
  const postgresDb = postgres.createDatabaseAndInstallMetrics(name);

  const initDb = initDatabase();
  return installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      postgres: postgres.address,
      postgresDb: name,
      postgresSchema: name,
      postgresSecretName: postgres.secretName,
      participantAdminUserNameFrom,
      disableAutoInit,
      metrics: {
        enable: true,
      },
      additionalJvmOptions: jmxOptions(),
      init: initDb && { initDb },
    },
    {
      dependsOn: dependsOn.concat([postgresDb]),
    }
  );
}
