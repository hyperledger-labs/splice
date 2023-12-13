import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

import { jmxOptions } from '../../common/src/jmx';
import { Postgres } from './postgres';

export function installDomain(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres
): pulumi.Resource {
  const sanitizedName = name.replace('-', '_');

  const mediatorDbName = `${sanitizedName}_mediator`;
  const mediatorDb = postgres.createDatabase(mediatorDbName);

  const sequencerDbName = `${sanitizedName}_sequencer`;
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
  const postgresDbName = 'participant';

  const postgresDb = postgres.createDatabase(postgresDbName);
  return installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      postgres: postgres.address,
      postgresDb: postgresDbName,
      postgresSchema: postgresDbName,
      postgresSecretName: postgres.secretName,
      participantAdminUserNameFrom,
      disableAutoInit,
      metrics: {
        enable: true,
      },
      additionalJvmOptions: jmxOptions(),
    },
    {
      dependsOn: dependsOn.concat([postgresDb]),
    }
  );
}
