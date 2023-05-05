import { installCNHelmChart } from "./helm";
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

// Note that for now this assumes the entire cluster is under this scripts's control,
// i.e. it was only initialized with the `infrastructure` pulumi, no other `cncluster` scripts (specifically, no other secrets or namespaces created).

const svNamespace = new k8s.core.v1.Namespace("sv-1", {
  metadata: {
    name: "sv-1",
  },
});

// TODO(#4521): make these configurable
const localCharts = true;
const version = "0.1.1-snapshot.20230505.2313.0.48a0243f";
const TARGET_CLUSTER = "staging";
const AUTH0_DOMAIN = process.env.AUTH0_DOMAIN;
const SV_WALLET_USER_ID = "auth0|64553aa683015a9687d9cc2e";

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

installCNHelmChart(
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
