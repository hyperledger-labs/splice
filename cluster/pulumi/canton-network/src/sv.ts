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
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installCometBftNode } from './cometbft';
import { installDomain, installParticipant } from './ledger';

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
      sponsorRelease?: k8s.helm.v3.Release;
      sponsorApiUrl: string;
    };

export async function installSvNode(
  auth0Client: Auth0Client,
  nodename: string,
  onboardingName: string,
  validatorWalletUser: string,
  onboarding: SvOnboarding,
  withScan = false
): Promise<k8s.helm.v3.Release> {
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
    );

  const postgresDb = postgres.installPostgres(xns, 'postgres');

  const domain =
    onboarding.type == 'found-collective' ? [installDomain(xns, 'global-domain', postgresDb)] : [];

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
    dependsOn.concat(domain)
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
    globalDomainUrl: 'http://global-domain.sv-1:5008',
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
      globalDomainUrl: 'http://global-domain.sv-1:5008',
    },
    [svApp]
  );

  return svApp;
}
