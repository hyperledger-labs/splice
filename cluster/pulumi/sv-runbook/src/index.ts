import { installCNHelmChart, installCNHelmChartByNamespaceName } from "./helm";
import {
  /*configureSecrets, */
  directoryUserParticipantSecret,
  imagePullSecret,
  scanUserParticipantSecret,
  sv1UserParticipantSecret,
  sv1UserValidatorParticipantSecret,
  svAppSecret,
  svValidatorSecrets,
  svcUserParticipantSecret,
} from "./secrets";
import * as k8s from "@pulumi/kubernetes";
import * as pulumi from "@pulumi/pulumi";

// Note that for now this assumes the entire cluster is under this scripts's control,
// i.e. it was only initialized with the `infrastructure` pulumi, no other `cncluster` scripts (specifically, no other secrets or namespaces created).

const svNamespace = new k8s.core.v1.Namespace("sv-1", {
  metadata: {
    name: "sv-1",
  },
});

// TODO(#4521): make these configurable
const localCharts = true; // Whether to use helm charts generated locally or taken from the artifactory (the latter being for externally released versions)
const version = "0.1.1-snapshot.20230505.2313.0.48a0243f"; // Artifacts version, if localCharts == false
const TARGET_CLUSTER = "scratchc"; // Cluster in which the global domain is running
const AUTH0_DOMAIN = process.env.AUTH0_DOMAIN; // Auth tenant domain
const SV_WALLET_USER_ID = "auth0|64553aa683015a9687d9cc2e"; // admin@sv.com at the sv-test tenant
const CLUSTER_BASENAME = "sv"; // Cluster in which this chart is being installed
const CLUSTER_IP = "34.133.96.35"; // IP of the cluster in which this chart is being installed

// Copied from ${REPO_ROOT}/apps/app/src/pack/examples/sv/sv-onboarding.conf
// TODO(#4521): make sure it's OK to reuse these once automated
const SV_NAME = "svTest";
const SV_PUBLIC_KEY =
  "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7uz+zW1YcPJIl+TKqXv6/dfxcx+3ISVFgP6m2saeQ0l6r2lNW+WLfq+HUMcycxX9t6bUJ5kyEebYyfk9JW18KA==";
const SV_PRIVATE_KEY =
  "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgdRTS3iLr8rPFaLUBbVcu8qYxklmMzQo/4UXcULYESm2hRANCAATu7P7NbVhw8kiX5Mqpe/r91/FzH7chJUWA/qbaxp5DSXqvaU1b5Yt+r4dQxzJzFf23ptQnmTIR5tjJ+T0lbXwo";

const imagePullDeps = localCharts ? [] : imagePullSecret(svNamespace);

const postgres = installCNHelmChart(
  svNamespace,
  "postgres",
  "cn-postgres",
  {},
  localCharts,
  version
);

const participant = installCNHelmChart(
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

const validator = installCNHelmChart(
  svNamespace,
  "validator",
  "cn-validator",
  // TODO(#4384): move these values into a file and distribute it with the release
  {
    participant_address: "participant",
    svSponsorPort: "5014",
    svSponsorAddress: `https://${TARGET_CLUSTER}.network.canton.global`,
    scanPort: "5012",
    scanAddress: `https://${TARGET_CLUSTER}.network.canton.global`,
    validatorWalletUser: SV_WALLET_USER_ID,
    clusterUrl: `${TARGET_CLUSTER}.network.canton.global`,
    auth: {
      // TODO(#4552): support arbitrary audience
      audience: "https://canton.network.global",
      jwksUrl: `https://${AUTH0_DOMAIN}/.well-known/jwks.json`,
    },
  },
  localCharts,
  version,
  imagePullDeps.concat([participant]).concat(svValidatorSecrets(svNamespace))
);

const sv = installCNHelmChart(
  svNamespace,
  "sv-1",
  "cn-sv-node",
  // TODO(#4384): move these values into a file and distribute it with the release
  {
    joinWithKeyOnboarding: {
      sponsorApiPort: 5014,
      sponsorApiUrl: `https://${TARGET_CLUSTER}.network.canton.global`,
      svcApiAddress: `${TARGET_CLUSTER}.network.canton.global`,
      keyName: SV_NAME,
      publicKey: SV_PUBLIC_KEY,
      privateKey: SV_PRIVATE_KEY,
    },
    auth: {
      // TODO(#4552): support arbitrary audience
      audience: "https://canton.network.global",
      jwksUrl: `https://${AUTH0_DOMAIN}/.well-known/jwks.json`,
    },
  },
  localCharts,
  version,
  imagePullDeps
    .concat([validator, participant])
    .concat(svAppSecret(svNamespace))
);

const docsNamespace = new k8s.core.v1.Namespace("docs", {
  metadata: {
    name: "docs",
  },
});

const docs = installCNHelmChart(
  docsNamespace,
  "docs",
  "cn-docs",
  {},
  localCharts,
  version
);

const infraStack = new pulumi.StackReference(`infra.${CLUSTER_BASENAME}`);
installCNHelmChartByNamespaceName(
  infraStack.getOutput("ingressNs") as pulumi.Output<string>,
  "cluster-ingress",
  "cn-cluster-ingress",
  // TODO(#4384): move these values into a file and distribute it with the release
  {
    enableIngressModes: "sv-external",
    cluster: {
      networkSettings: {
        externalIPRanges: [
          "35.194.81.56/32",
          "35.198.147.95/32",
          "35.189.40.124/32",
          "34.132.91.75/32",
        ],
      },
      ipAddress: CLUSTER_IP,
      // TODO(#4521): using basename diverges from the runbook instructions, and is currently
      // required because we store the tls in secret cn-<basename>net-tls, as opposed to
      // cn-net-tls as in the runbook.
      basename: "sv",
    },
  },
  localCharts,
  version,
  imagePullDeps.concat([sv, validator, docs])
);
