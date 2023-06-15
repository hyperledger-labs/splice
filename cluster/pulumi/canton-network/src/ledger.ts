import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

export function installDomain(
  xns: ExactNamespace,
  name: string,
  postgresDb: pulumi.Output<string>
): k8s.helm.v3.Release {
  return installCNHelmChart(xns, name, 'cn-domain', {
    postgres: postgresDb,
  });
}

export function installParticipant(
  xns: ExactNamespace,
  name: string,
  postgresDb: pulumi.Output<string>,
  extraDomains: Domain[],
  participantUsers: ParticipantUser[],
  extraEnvVars: k8s.types.input.core.v1.EnvVar[],
  dependsOn: pulumi.Resource[] = []
): k8s.helm.v3.Release {
  return installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      postgres: postgresDb,
      postgresSchema: xns.logicalName + '_participant',
      extraDomains: JSON.stringify(extraDomains),
      participantUsers: JSON.stringify(participantUsers),
      extraEnvVars,
    },
    dependsOn
  );
}

export type ParticipantUser = {
  name: StringOrEnv;
  actAs: Party[];
  primaryParty?: Party;
  readAs: Party[];
  admin: boolean;
};

type Party = { fromUser: StringOrEnv } | { allocate: StringOrEnv };

type Domain = { alias: StringOrEnv; url: StringOrEnv };

type StringOrEnv = string | { env: string };
