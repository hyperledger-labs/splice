import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

import { domainFeesConfig } from './domainFeesCfg';

export function installDomain(
  xns: ExactNamespace,
  name: string,
  postgresDb: pulumi.Output<string>,
  postgresPassword: pulumi.Input<string>
): pulumi.Resource {
  return installCNHelmChart(xns, name, 'cn-domain', {
    postgres: postgresDb,
    postgresPassword,
  });
}

export function installGlobalDomain(
  xns: ExactNamespace,
  name: string,
  postgresDb: pulumi.Output<string>,
  withDomainFees: boolean,
  postgresPassword: pulumi.Input<string>,
  postgresSequencerPassword: undefined | pulumi.Input<string> = undefined
): pulumi.Resource {
  if (withDomainFees) {
    console.error('Running with domain fees');
  }
  return installCNHelmChart(xns, name, 'cn-global-domain', {
    postgres: postgresDb,
    postgresPassword,
    sequencerDriver: {
      address: 'postgres.sv-1',
      password: postgresSequencerPassword,
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
  postgresDb: pulumi.Output<string>,
  extraDomains: Domain[],
  participantUserEnvVars: string[],
  extraEnvVars: k8s.types.input.core.v1.EnvVar[],
  postgresPassword: pulumi.Input<string>,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource {
  return installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      postgres: postgresDb,
      postgresPassword,
      postgresSchema: xns.logicalName + '_participant',
      extraDomains: JSON.stringify(extraDomains),
      participantUsers: JSON.stringify(participantUserEnvVars),
      extraEnvVars,
    },
    dependsOn
  );
}

type Domain = { alias: StringOrEnv; url: StringOrEnv };

type StringOrEnv = string | { env: string };
