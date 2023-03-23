import * as k8s from "@pulumi/kubernetes";

import * as postgres from "./postgres";

import {
  auth0UserNameEnvVar,
  installAuth0Secret,
  installAuth0UISecret,
} from "./auth0";

import { exactNamespace, installCNHelmChart } from "./utils";
import { installDomain, installParticipant } from "./ledger";
import { installClusterIngress } from "./ingress";

export function installSplitwell(
  svc: k8s.helm.v3.Release
): k8s.helm.v3.Release {
  const xns = exactNamespace("splitwell");

  const postgresDb = postgres.installPostgres(xns, "postgres");

  const domain = installDomain(xns, "domain", postgresDb);

  const participant = installParticipant(
    xns,
    "participant",
    postgresDb,
    [{ alias: "splitwell", url: "http://domain.splitwell:5008" }],
    [
      {
        actAs: [{ fromUser: "self" }],
        admin: true,
        name: {
          env: "CN_APP_SPLITWELL_VALIDATOR_LEDGER_API_AUTH_USER_NAME",
        },
        primaryParty: {
          allocate: "splitwell_validator_service_user",
        },
        readAs: [],
      },
    ],
    [auth0UserNameEnvVar("splitwell_validator", "validator")]
  );

  const dependsOn = [
    xns.ns,
    svc,
    installAuth0Secret(xns, "splitwell", "splitwell"),
    installAuth0Secret(xns, "validator", "splitwell_validator"),
    installAuth0Secret(xns, "wallet", "splitwell_wallet"),
    installAuth0UISecret(xns, "wallet", "splitwell"),
  ];

  return installCNHelmChart(
    xns,
    "splitwell",
    "cn-splitwell",
    {
      postgres: postgresDb,
    },
    dependsOn
  );
}
