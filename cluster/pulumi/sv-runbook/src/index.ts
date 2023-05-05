import { installCNHelmChart } from "./helm";
import {
  /*configureSecrets, */
  directoryUserParticipantSecret,
  imagePullSecret,
  scanUserParticipantSecret,
  sv1UserParticipantSecret,
  sv1UserValidatorParticipantSecret,
  svcUserParticipantSecret,
} from "./secrets";
import * as k8s from "@pulumi/kubernetes";

// Note that for now this assumes the entire cluster is under this scripts's control,
// i.e. it was only initialized with the `infrastructure` pulumi, no other `cncluster` scripts (specifically, no other secrets or namespaces created).

const svNamespace = new k8s.core.v1.Namespace("sv-1", {
  metadata: {
    name: "sv-1",
  },
});

// TODO(#4521): make these configurable
const localCharts = false;
const version = "0.1.1-snapshot.20230504.2281.0.e6f9ad9b";
const TARGET_CLUSTER = "staging";
const AUTH0_DOMAIN = process.env.AUTH0_DOMAIN;

const imagePullDeps = localCharts ? [] : imagePullSecret(svNamespace);

const postgres = installCNHelmChart(
  svNamespace,
  "postgres",
  "cn-postgres",
  {},
  localCharts,
  version
);

installCNHelmChart(
  svNamespace,
  "participant",
  "cn-participant",
  // TODO(#4384): move these values into a file and distribute it with the release
  {
    postgres: "postgres",
    globalDomain: {
      alias: "global",
      url: `http://${TARGET_CLUSTER}.network.canton.global:5008`,
    },
    auth: {
      jwksEndpoint: `https://${AUTH0_DOMAIN}/.well-known/jwks.json`,
      // TODO(#4552): support arbitrary audience
      targetAudience: "https://canton.network.global",
    },
  },
  localCharts,
  version,
  imagePullDeps.concat([
    postgres,
    sv1UserParticipantSecret(svNamespace),
    sv1UserValidatorParticipantSecret(svNamespace),
    scanUserParticipantSecret(svNamespace),
    directoryUserParticipantSecret(svNamespace),
    svcUserParticipantSecret(svNamespace),
  ])
);
