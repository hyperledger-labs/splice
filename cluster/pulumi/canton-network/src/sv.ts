import * as k8s from '@pulumi/kubernetes';

import * as postgres from './postgres';
import { auth0UserNameEnvVar, installAuth0Secret, installAuth0UISecret } from './auth0';
import type { Auth0Client } from './auth0types';
import { installDomain, installParticipant } from './ledger';
import { ChartValues, ExactNamespace, exactNamespace, installCNHelmChart } from './utils';

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
        name: { env: 'CN_APP_SVC_LEDGER_API_AUTH_USER_NAME' },
        primaryParty: { allocate: 'svc_party' },
        actAs: [{ fromUser: 'self' }],
        readAs: [],
        admin: true,
      },
      {
        name: { env: 'CN_APP_SCAN_LEDGER_API_AUTH_USER_NAME' },
        primaryParty: { fromUser: { env: 'CN_APP_SVC_LEDGER_API_AUTH_USER_NAME' } },
        actAs: [],
        readAs: [{ fromUser: { env: 'CN_APP_SVC_LEDGER_API_AUTH_USER_NAME' } }],
        admin: false,
      },
      {
        name: { env: 'CN_APP_DIRECTORY_LEDGER_API_AUTH_USER_NAME' },
        primaryParty: { fromUser: { env: 'CN_APP_SVC_LEDGER_API_AUTH_USER_NAME' } },
        actAs: [{ fromUser: { env: 'CN_APP_SVC_LEDGER_API_AUTH_USER_NAME' } }],
        readAs: [],
        admin: true,
      },
      {
        name: { env: 'CN_APP_SV_LEDGER_API_AUTH_USER_NAME' },
        actAs: [{ fromUser: { env: 'CN_APP_SVC_LEDGER_API_AUTH_USER_NAME' } }],
        readAs: [],
        admin: true,
      },
    ],
    [
      auth0UserNameEnvVar('directory'),
      auth0UserNameEnvVar('scan'),
      auth0UserNameEnvVar('sv', 'sv1'),
      auth0UserNameEnvVar('svc'),
      auth0UserNameEnvVar('validator'),
    ],
    [domain]
  );

  const dependsOn = [
    participant,
    await installAuth0Secret(auth0Client, xns, 'directory', 'directory'),
    await installAuth0Secret(auth0Client, xns, 'scan', 'scan'),
    await installAuth0Secret(auth0Client, xns, 'sv1', 'sv-1'),
    await installAuth0Secret(auth0Client, xns, 'svc', 'svc'),
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
    [auth0UserNameEnvVar('validator'), auth0UserNameEnvVar('sv')]
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
  ].concat(
    joinWithKey ? [installSvKeySecret(xns, joinWithKey.publicKey, joinWithKey.privateKey)] : []
  );

  if (nodename !== 'sv-1') {
    const participant = installSvParticipant(xns);

    if (nodename === 'sv-2') {
      installCNHelmChart(
        xns,
        'scan' + xns.logicalName,
        'cn-scan',
        {},
        dependsOn.concat([participant])
      );
    }
  }

  const participantAddress = nodename === 'sv-1' ? 'participant.svc' : 'participant';

  const values = {
    participantAddress,
    onboardingType: joinWithKey ? 'join-with-key' : 'found-collective',
    onboardingName,
  } as ChartValues;

  if (joinWithKey) {
    values.joinWithKeyOnboarding = {
      sponsorApiUrl: 'http://sv-app.sv-1:5014',
      svcApiAddress: 'svc-app.svc',
    };
  }

  const svApp = installCNHelmChart(xns, nodename + '-sv-app', 'cn-sv-node', values, dependsOn);

  installCNHelmChart(
    xns,
    'validator-' + xns.logicalName,
    'cn-validator',
    {
      participantAddress,
      additionalUsers: [],
      appDars: [],
      validatorWalletUser,
    },
    dependsOn.concat([svApp])
  );
}
