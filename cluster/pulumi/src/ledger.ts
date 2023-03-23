import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";

import * as postgres from "./postgres";

import {
  auth0UserNameEnvVar,
  installAuth0Secret,
  installAuth0UISecret,
} from "./auth0";

import { ExactNamespace, installCNHelmChart } from "./utils";

export function installDomain(
  xns: ExactNamespace,
  name: string,
  postgresDb: pulumi.Output<string>
): k8s.helm.v3.Release {
  return installCNHelmChart(xns, name, "cn-domain", {
    postgres: postgresDb,
  });
}

export function installParticipant(
  xns: ExactNamespace,
  name: string,
  postgresDb: pulumi.Output<string>,
  extraDomains: any,
  participantUsers: any,
  extraEnvVars: any
): k8s.helm.v3.Release {
  return installCNHelmChart(xns, name, "cn-participant", {
    postgres: postgresDb,
    postgresSchema: xns.logicalName + "_participant",
    extraDomains: JSON.stringify(extraDomains),
    participantUsers: JSON.stringify(participantUsers),
    extraEnvVars,
  });
}
