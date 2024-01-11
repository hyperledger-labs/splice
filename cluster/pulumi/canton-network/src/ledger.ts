import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { ExactNamespace, installCNHelmChart, sanitizedForPostgres } from 'cn-pulumi-common';

import { jmxOptions } from '../../common/src/jmx';
import { initDatabase, Postgres } from './postgres';

export function installParticipant(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres,
  participantAdminUserNameFrom: k8s.types.input.core.v1.EnvVarSource,
  disableAutoInit = false,
  dependsOn: pulumi.Resource[] = []
): Release {
  const pgName = sanitizedForPostgres(name);
  const postgresDb = postgres.createDatabase(pgName);

  const initDb = initDatabase();
  return installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      persistence: {
        databaseName: pgName,
        schema: pgName,
        host: postgres.address,
        secretName: postgres.secretName,
      },
      participantAdminUserNameFrom,
      disableAutoInit,
      metrics: {
        enable: true,
      },
      additionalJvmOptions: jmxOptions(),
      init: initDb && { initDb },
    },
    [postgresDb],
    {
      dependsOn,
    }
  );
}
