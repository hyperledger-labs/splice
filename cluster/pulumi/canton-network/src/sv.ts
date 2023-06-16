import * as k8s from '@pulumi/kubernetes';
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

export async function installSVC(auth0Client: Auth0Client): Promise<k8s.helm.v3.Release> {
  const xns = exactNamespace('svc');

  const postgresDb = postgres.installPostgres(xns, 'postgres');

  const domain = installDomain(xns, 'global-domain', postgresDb);

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
    [auth0UserNameEnvVar('sv', 'sv1')],
    [domain]
  );

  const dependsOn = [
    participant,
    await installAuth0Secret(auth0Client, xns, 'sv1', 'sv-1'),
    await installAuth0Secret(auth0Client, xns, 'validator', 'validator'),
  ];

  return installCNHelmChart(
    xns,
    'svc',
    'cn-svc',
    {
      postgres: postgresDb,
    },
    dependsOn
  );
}

function installSvParticipant(xns: ExactNamespace): k8s.helm.v3.Release {
  const postgresDb = postgres.installPostgres(xns, 'postgres');

  return installParticipant(
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
    [auth0UserNameEnvVar('sv')]
  );
}

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

export async function installSvNode(
  auth0Client: Auth0Client,
  svc: k8s.helm.v3.Release,
  nodename: string,
  onboardingName: string,
  validatorWalletUser: string,
  joinWithKey?: { publicKey: string; privateKey: string }
): Promise<void> {
  const xns = exactNamespace(nodename);

  const dependsOn = [
    svc,
    await installAuth0Secret(auth0Client, xns, 'sv', nodename),
    await installAuth0UISecret(auth0Client, xns, 'sv', nodename),
    await installAuth0Secret(auth0Client, xns, 'validator', 'validator'),
    await installAuth0UISecret(auth0Client, xns, 'wallet', 'wallet'),
    await installAuth0UISecret(auth0Client, xns, 'directory', 'directory'),
  ].concat(
    joinWithKey ? [installSvKeySecret(xns, joinWithKey.publicKey, joinWithKey.privateKey)] : []
  );

  const isNodeSv1 = nodename === 'sv-1';
  if (!isNodeSv1) {
    const participant = installSvParticipant(xns);

    if (nodename === 'sv-2') {
      installCNHelmChart(
        xns,
        'scan-' + xns.logicalName,
        'cn-scan',
        {},
        // TODO(#5597) this really should depend on sv2
        dependsOn.concat([participant])
      );
    }
  }
  installCometBftNode(xns, nodename, onboardingName);

  const participantAddress = isNodeSv1 ? 'participant.svc' : 'participant';

  const svValues = {
    participantAddress,
    onboardingType: joinWithKey ? 'join-with-key' : 'found-collective',
    onboardingName,
    cometBFT: {
      enabled: true,
      automationEnabled: false,
      connectionUri: `http://cometbft-${nodename}-cometbft-rpc:26657`,
    },
    globalDomainUrl: 'http://global-domain.svc:5008',
  } as ChartValues;

  if (joinWithKey) {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: 'http://sv-app.sv-1:5014',
    };
  }

  // TODO(#5597) this really should depend on the sv's participant
  const svApp = installCNHelmChart(xns, nodename + '-sv-app', 'cn-sv-node', svValues, dependsOn);

  installCNHelmChart(
    xns,
    'validator-' + xns.logicalName,
    'cn-validator',
    {
      participantAddress,
      additionalUsers: [],
      appDars: [],
      validatorWalletUser,
      globalDomainUrl: 'http://global-domain.svc:5008',
    },
    dependsOn.concat([svApp])
  );
}
