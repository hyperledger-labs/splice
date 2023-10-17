import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Service } from '@pulumi/kubernetes/core/v1';
import { ExactNamespace, installCNHelmChart, domainFeesConfig } from 'cn-pulumi-common';

import { Postgres } from './postgres';

export function installDomain(
  xns: ExactNamespace,
  name: string,
  postgresDb: Postgres
): pulumi.Resource {
  return installCNHelmChart(xns, name, 'cn-domain', {
    postgres: postgresDb.address,
    postgresPassword: postgresDb.password,
  });
}

export function installGlobalDomain(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres,
  sequencer: PostgresSequencer | CometBftSequencer
): pulumi.Resource {
  return installCNHelmChart(xns, name, 'cn-global-domain', {
    postgres: postgres.address,
    postgresPassword: postgres.password,
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
            password: sequencer.postgres.password,
          },
    trafficControl: {
      enabled: true,
      baseRate: domainFeesConfig.baseRate,
      maxBurstDuration: domainFeesConfig.maxBurstDuration,
    },
    metrics: {
      enable: true,
    },
  });
}

export function installParticipant(
  xns: ExactNamespace,
  name: string,
  postgresDb: Postgres,
  participantAdminUserNameFrom: k8s.types.input.core.v1.EnvVarSource,
  disableAutoInit = false,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource {
  return installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      postgres: postgresDb.address,
      postgresPassword: postgresDb.password,
      postgresSchema: xns.logicalName + '_participant',
      participantAdminUserNameFrom,
      disableAutoInit,
      metrics: {
        enable: true,
      },
    },
    dependsOn
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
