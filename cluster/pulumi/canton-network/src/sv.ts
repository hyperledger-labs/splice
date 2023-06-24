import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVar,
  installAuth0Secret,
  installAuth0UISecret,
  ChartValues,
  ExactNamespace,
  exactNamespace,
  installCNHelmChart,
  CLUSTER_BASENAME,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installCometBftNode } from './cometbft';
import { installGlobalDomain, installParticipant } from './ledger';

// btoa is only available in DOM so inline the definition here.
const btoa = (s: string) => Buffer.from(s).toString('base64');

export function installSvKeySecret(
  xns: ExactNamespace,
  publicKey: string,
  privateKey: string
): k8s.core.v1.Secret {
  const secretName = 'cn-app-sv-key';
  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-key`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        public: btoa(publicKey),
        private: btoa(privateKey),
      },
    },
    {
      dependsOn: [xns.ns],
    }
  );
}

export type SvOnboarding =
  | { type: 'found-collective' }
  | {
      type: 'join-with-key';
      publicKey: string;
      privateKey: string;
      sponsorRelease?: pulumi.Resource;
      sponsorApiUrl: string;
    };

export type ValidatorOnboarding = { name: string; expiresIn: string; secret: string };

export const validatorOnboardingSecretName = (onboarding: ValidatorOnboarding): string =>
  `cn-app-validator-onboarding-${onboarding.name}`;

export function installValidatorOnboardingSecret(
  xns: ExactNamespace,
  onboarding: ValidatorOnboarding
): k8s.core.v1.Secret {
  const secretName = validatorOnboardingSecretName(onboarding);
  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-validator-onboarding-${onboarding.name}`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        secret: btoa(onboarding.secret),
      },
    },
    {
      dependsOn: [xns.ns],
    }
  );
}

export async function installSvNode(
  auth0Client: Auth0Client,
  nodename: string,
  onboardingName: string,
  validatorWalletUser: string,
  onboarding: SvOnboarding,
  isDevNet: boolean,
  withDomainFees: boolean,
  withScan = false,
  withDirectoryBackend = false,
  expectedValidatorOnboardings: ValidatorOnboarding[] = []
): Promise<pulumi.Resource> {
  const xns = exactNamespace(nodename);

  const dependsOn = (
    [
      await installAuth0Secret(auth0Client, xns, 'sv', nodename),
      await installAuth0UISecret(auth0Client, xns, 'sv', nodename),
      await installAuth0Secret(auth0Client, xns, 'validator', 'validator'),
      await installAuth0UISecret(auth0Client, xns, 'wallet', 'wallet'),
      await installAuth0UISecret(auth0Client, xns, 'directory', 'directory'),
    ] as pulumi.Resource[]
  )
    .concat(
      onboarding.type == 'join-with-key'
        ? [installSvKeySecret(xns, onboarding.publicKey, onboarding.privateKey)]
        : []
    )
    .concat(
      onboarding.type == 'join-with-key' && onboarding.sponsorRelease
        ? [onboarding.sponsorRelease]
        : []
    )
    .concat(
      expectedValidatorOnboardings.map(onboarding =>
        installValidatorOnboardingSecret(xns, onboarding)
      )
    );

  const postgresDb = postgres.installPostgres(xns, 'postgres');

  const domain = installGlobalDomain(xns, 'global-domain', postgresDb, withDomainFees);

  const participant = installParticipant(
    xns,
    'participant',
    postgresDb,
    [],
    [
      {
        actAs: [],
        admin: true,
        name: {
          env: 'CN_APP_SV_LEDGER_API_AUTH_USER_NAME',
        },
        readAs: [],
      },
    ],
    [auth0UserNameEnvVar('sv')],
    dependsOn.concat([domain])
  );
  installCometBftNode(xns, nodename, onboardingName);

  const svValues = {
    onboardingType: onboarding.type,
    onboardingName,
    cometBFT: {
      enabled: true,
      automationEnabled: false,
      connectionUri: `http://cometbft-${nodename}-cometbft-rpc:26657`,
    },
    globalDomainUrl: 'http://global-domain-sequencer.sv-1:5008',
    foundingSvApiUrl: 'http://sv-app.sv-1:5014',
    domain:
      // defaults for ports and address are fine,
      // we need to include a dummy value though
      // because helm does not distinguish between an empty object and unset.
      { enable: true },
    expectedValidatorOnboardings: expectedValidatorOnboardings.map(onboarding => ({
      expiresIn: onboarding.expiresIn,
      secretFrom: {
        secretKeyRef: {
          name: validatorOnboardingSecretName(onboarding),
          key: 'secret',
          optional: false,
        },
      },
    })),
    isDevNet: isDevNet,
  } as ChartValues;

  if (onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: onboarding.sponsorApiUrl,
    };
  }

  const svApp = installCNHelmChart(xns, nodename + '-sv-app', 'cn-sv-node', svValues, [
    participant,
  ]);

  if (withScan) {
    installCNHelmChart(xns, 'scan-' + xns.logicalName, 'cn-scan', {}, [svApp]);
  }

  installCNHelmChart(
    xns,
    'validator-' + xns.logicalName,
    'cn-validator',
    {
      additionalUsers: [],
      appDars: [],
      validatorWalletUser,
      globalDomainUrl: 'http://global-domain-sequencer.sv-1:5008',
      foundingSvApiUrl: 'http://sv-app.sv-1:5014',
    },
    [svApp]
  );

  installCNHelmChart(
    xns,
    'ingress-sv-' + xns.logicalName,
    'cn-cluster-ingress-sv',
    {
      withScan: withScan,
      withDirectoryBackend: withDirectoryBackend,
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: xns.logicalName,
      },
    },
    [xns.ns]
  );

  return svApp;
}
