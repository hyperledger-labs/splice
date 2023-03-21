import * as k8s from "@pulumi/kubernetes";

import * as postgres from "./postgres";

import {
  auth0UserNameEnvVar,
  installAuth0Secret,
  installAuth0UISecret,
} from "./auth0";

import { exactNamespace, installCNHelmChart } from "./utils";
import { installParticipant } from "./ledger";

export function installValidator(
  svc: k8s.helm.v3.Release,
  name: string
): k8s.helm.v3.Release {
  const xns = exactNamespace(name);

  const postgresDb = postgres.installPostgres(xns, "postgres");

  const participant = installParticipant(
    xns,
    "p",
    postgresDb,
    [{ alias: "splitwell", url: "http://domain.splitwell:5008" }],
    [
      {
        actAs: [{ fromUser: "self" }],
        admin: true,
        name: {
          env: "CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME",
        },
        primaryParty: {
          allocate: "validator1_validator_service_user",
        },
        readAs: [],
      },
    ],
    [auth0UserNameEnvVar("validator")]
  );

  const dependsOn = [
    svc,
    xns.ns,
    installAuth0Secret(xns, "validator", "validator"),
    installAuth0Secret(xns, "wallet", "wallet"),

    installAuth0UISecret(xns, "directory", "directory"),
    installAuth0UISecret(xns, "splitwell", "splitwell"),
    installAuth0UISecret(xns, "wallet", "wallet"),
  ];

  return installCNHelmChart(
    xns,
    "validator-" + xns.logicalName,
    "cn-validator",
    {
      postgres: postgresDb,
    },
    dependsOn
  );
}
