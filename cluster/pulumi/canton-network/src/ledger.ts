import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

import { domainFeesConfig } from './domainFeesCfg';
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
  postgresDb: pulumi.Output<string>,
  withDomainFees: boolean,
  postgres: Postgres,
  sequencerPostgres: Postgres
): pulumi.Resource {
  if (withDomainFees) {
    console.error('Running with domain fees');
  }
  return installCNHelmChart(xns, name, 'cn-global-domain', {
    postgres: postgres.address,
    postgresPassword: postgres.password,
    sequencerDriver: {
      address: sequencerPostgres.address,
      password: sequencerPostgres.password,
    },
    trafficControl: withDomainFees
      ? {
          enabled: true,
          baseRate: domainFeesConfig.baseRate,
          maxBurstDuration: domainFeesConfig.maxBurstDuration,
        }
      : {},
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
    },
    dependsOn
  );
}
