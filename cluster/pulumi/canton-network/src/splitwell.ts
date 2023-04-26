import * as k8s from "@pulumi/kubernetes";

import * as postgres from "./postgres";

import {
  auth0UserNameEnvVar,
  installAuth0Secret,
  installAuth0UISecret,
} from "./auth0";

import { exactNamespace, fixedTokens, installCNHelmChart } from "./utils";
import { installDomain, installParticipant } from "./ledger";

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
    [auth0UserNameEnvVar("splitwell_validator", "validator")],
    [domain]
  );

  installCNHelmChart(
    xns,
    "splitwell-app",
    "cn-splitwell-app",
    {
      postgres: postgresDb,
    },
    [participant]
  );

  const dependsOn = [
    svc,
    installAuth0Secret(xns, "splitwell", "splitwell"),
    installAuth0Secret(xns, "validator", "splitwell_validator"),
    installAuth0Secret(xns, "svc", "svc"),
    installAuth0Secret(xns, "scan", "scan"),
    installAuth0Secret(xns, "directory", "directory"),
    installAuth0UISecret(xns, "wallet", "splitwell"),
  ];

  const fixedTokenConfig = fixedTokens()
    ? [
        "_client_credentials_auth_config = null",
        "_client_credentials_auth_config = {",
        '  type = "static"',
        "  token = ${CN_APP_VALIDATOR_LEDGER_API_AUTH_TOKEN}",
        "}",
      ]
    : [];

  return installCNHelmChart(
    xns,
    "splitwell",
    "cn-validator",
    {
      postgres: postgresDb,
      additionalUsers: [auth0UserNameEnvVar("splitwell")],
      additionalConfig: [
        ...fixedTokenConfig,
        "canton.validator-apps.validator_backend.app-instances.splitwise = {",
        "  service-user = ${?CN_APP_SPLITWELL_LEDGER_API_AUTH_USER_NAME}",
        "  wallet-user = ${?CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME}",
        '  dars = ["cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar"]',
        "}",
      ].join("\n"),
    },
    dependsOn
  );
}
