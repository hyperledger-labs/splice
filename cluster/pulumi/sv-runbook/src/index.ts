import { installCNHelmChart, installCNHelmChartByNamespaceName } from "./helm";
import {
  /*configureSecrets, */
  directoryUserParticipantSecret,
  imagePullSecret,
  imagePullSecretByNamespaceName,
  scanUserParticipantSecret,
  sv1UserParticipantSecret,
  sv1UserValidatorParticipantSecret,
  svAppSecrets,
  svValidatorSecrets,
  svcUserParticipantSecret,
} from "./secrets";
import { exactNamespace, requiredEnv } from "./utils";
import * as pulumi from "@pulumi/pulumi";

// Note that for now this assumes the entire cluster is under this scripts's control,
// i.e. it was only initialized with the `infrastructure` pulumi, no other `cncluster` scripts (specifically, no other secrets or namespaces created).

const version = process.env.CHARTS_VERSION;
const localCharts = version == "" || version == undefined; // Whether to use helm charts generated locally or taken from the artifactory (the latter being for externally released versions)
const CLUSTER_BASENAME = requiredEnv(
  "GCP_CLUSTER_BASENAME",
  "The cluster in which this chart is being installed"
);
const AUTH0_DOMAIN = requiredEnv("AUTH0_DOMAIN", "the Auth0 tenant domain"); // auth0 plugin requires this to be defined anyway, so we just reuse that
const TARGET_CLUSTER = requiredEnv(
  "TARGET_CLUSTER",
  "the cluster in which the global domain is running"
);
const SV_WALLET_USER_ID =
  process.env.SV_WALLET_USER_ID || "auth0|64553aa683015a9687d9cc2e"; // Default to admin@sv.com at the sv-test tenant by default
const infraStack = new pulumi.StackReference(`infra.${CLUSTER_BASENAME}`);
const CLUSTER_IP = infraStack.getOutput("ingressIp"); // IP of the cluster in which this chart is being installed
const SV_NAMESPACE = process.env.SV_NAMESPACE || "sv";

console.log(
  localCharts
    ? "Using locally built charts"
    : `Using charts from the artifactory, version ${version}`
);
console.log(`TARGET_CLUSTER: ${TARGET_CLUSTER}`);
console.log(`Installing SV node in namespace: ${SV_NAMESPACE}`);

// Copied from ${REPO_ROOT}/apps/app/src/pack/examples/sv/sv-onboarding.conf
// TODO(#4443): make sure it's OK to reuse these once automated
const SV_NAME = "DA-Test-Node";
const SV_PUBLIC_KEY =
  "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7uz+zW1YcPJIl+TKqXv6/dfxcx+3ISVFgP6m2saeQ0l6r2lNW+WLfq+HUMcycxX9t6bUJ5kyEebYyfk9JW18KA==";
const SV_PRIVATE_KEY =
  "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgdRTS3iLr8rPFaLUBbVcu8qYxklmMzQo/4UXcULYESm2hRANCAATu7P7NbVhw8kiX5Mqpe/r91/FzH7chJUWA/qbaxp5DSXqvaU1b5Yt+r4dQxzJzFf23ptQnmTIR5tjJ+T0lbXwo";

const svNamespace = exactNamespace(SV_NAMESPACE);

const svImagePullDeps = localCharts ? [] : imagePullSecret(svNamespace);

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
  svImagePullDeps.concat([
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
    participantAddress: "participant",
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
  svImagePullDeps.concat([participant]).concat(svValidatorSecrets(svNamespace))
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
      publicKey: SV_PUBLIC_KEY,
      privateKey: SV_PRIVATE_KEY,
    },
    onboardingName: SV_NAME,
    auth: {
      // TODO(#4552): support arbitrary audience
      audience: "https://canton.network.global",
      jwksUrl: `https://${AUTH0_DOMAIN}/.well-known/jwks.json`,
    },
  },
  localCharts,
  version,
  svImagePullDeps
    .concat([validator, participant])
    .concat(svAppSecrets(svNamespace))
);

const ingressImagePullDeps = localCharts
  ? []
  : imagePullSecretByNamespaceName("cluster-ingress");
installCNHelmChartByNamespaceName(
  infraStack.getOutput("ingressNs") as pulumi.Output<string>,
  "cluster-ingress",
  "cn-cluster-ingress-sv",
  // TODO(#4384): move these values into a file and distribute it with the release
  {
    enableIngressModes: "sv-external",
    svNamespace: SV_NAMESPACE,
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
      // TODO(#4443): using basename diverges from the runbook instructions, and is currently
      // required because we store the tls in secret cn-<basename>net-tls, as opposed to
      // cn-net-tls as in the runbook.
      basename: CLUSTER_BASENAME,
    },
  },
  localCharts,
  version,
  ingressImagePullDeps.concat([sv, validator])
);
