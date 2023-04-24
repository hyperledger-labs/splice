import * as k8s from "@pulumi/kubernetes";

import * as postgres from "./postgres";

import { auth0UserNameEnvVar, installAuth0Secret } from "./auth0";

import { exactNamespace, installCNHelmChart } from "./utils";
import { installDomain, installParticipant } from "./ledger";

function svNodeParty(name: string): any {
  return {
    actAs: [
      {
        fromUser: "self",
      },
      {
        fromUser: {
          env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME",
        },
      },
    ],
    admin: true,
    name: {
      env: `CN_APP_${name.toUpperCase()}_LEDGER_API_AUTH_USER_NAME`,
    },
    primaryParty: {
      allocate: name,
    },
    readAs: [],
  };
}

export function installSVC(): k8s.helm.v3.Release {
  const xns = exactNamespace("svc");

  const postgresDb = postgres.installPostgres(xns, "postgres");

  const domain = installDomain(xns, "global-domain", postgresDb);

  const participant = installParticipant(
    xns,
    "participant",
    postgresDb,
    [],
    [
      {
        actAs: [
          {
            fromUser: "self",
          },
        ],
        admin: true,
        name: {
          env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME",
        },
        primaryParty: {
          allocate: "svc_party",
        },
        readAs: [],
      },
      {
        actAs: [],
        admin: false,
        name: {
          env: "CN_APP_SCAN_LEDGER_API_AUTH_USER_NAME",
        },
        primaryParty: {
          fromUser: {
            env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME",
          },
        },
        readAs: [
          {
            fromUser: {
              env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME",
            },
          },
        ],
      },
      {
        actAs: [
          {
            fromUser: {
              env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME",
            },
          },
        ],
        admin: true,
        name: {
          env: "CN_APP_DIRECTORY_LEDGER_API_AUTH_USER_NAME",
        },
        primaryParty: {
          fromUser: {
            env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME",
          },
        },
        readAs: [],
      },
      svNodeParty("sv1"),
      svNodeParty("sv2"),
      svNodeParty("sv3"),
      svNodeParty("sv4"),
    ],
    [
      auth0UserNameEnvVar("sv1"),
      auth0UserNameEnvVar("sv2"),
      auth0UserNameEnvVar("sv3"),
      auth0UserNameEnvVar("sv4"),
      auth0UserNameEnvVar("svc"),
      auth0UserNameEnvVar("scan"),
      auth0UserNameEnvVar("directory"),
    ],
    [domain]
  );

  const dependsOn = [
    participant,
    installAuth0Secret(xns, "sv1", "sv-1"),
    installAuth0Secret(xns, "sv2", "sv-2"),
    installAuth0Secret(xns, "sv3", "sv-3"),
    installAuth0Secret(xns, "sv4", "sv-4"),
    installAuth0Secret(xns, "scan", "scan"),
    installAuth0Secret(xns, "directory", "directory"),
    installAuth0Secret(xns, "svc", "svc"),
  ];

  return installCNHelmChart(
    xns,
    "svc",
    "cn-svc",
    {
      postgres: postgresDb,
    },
    dependsOn
  );
}

export function installSvNode(svc: k8s.helm.v3.Release, nodename: string) {
  const xns = exactNamespace(nodename);

  const dependsOn = [svc, installAuth0Secret(xns, "sv", nodename)];

  installCNHelmChart(
    xns,
    nodename + "-sv-app",
    "cn-sv-node",
    {
      onboardingType:
        nodename === "sv-1" ? "found-collective" : "join-via-svc-app",
    },
    dependsOn
  );
}
