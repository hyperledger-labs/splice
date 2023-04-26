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
    "participant",
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

  installCNHelmChart(xns, "directory-web-ui", "cn-directory-web-ui", {}, [
    installAuth0UISecret(xns, "directory", "directory"),
  ]);
  installCNHelmChart(xns, "splitwell-web-ui", "cn-splitwell-web-ui", {}, [
    installAuth0UISecret(xns, "splitwell", "splitwell"),
  ]);

  const dependsOn = [
    svc,
    xns.ns,
    participant,
    installAuth0Secret(xns, "validator", "validator"),
    installAuth0Secret(xns, "svc", "svc"),
    installAuth0Secret(xns, "scan", "scan"),
    installAuth0Secret(xns, "directory", "directory"),
    installAuth0UISecret(xns, "wallet", "wallet"),
  ];

  return installCNHelmChart(
    xns,
    "validator-" + xns.logicalName,
    "cn-validator",
    {
      postgres: postgresDb,
      additionalUsers: [],
      appDars: [
        "cn-node-0.1.0-SNAPSHOT/dars/directory-service-0.1.0.dar",
        "cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar",
      ],
    },
    dependsOn
  );
}
