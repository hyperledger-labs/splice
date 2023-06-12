import * as k8s from '@pulumi/kubernetes';

import * as postgres from './postgres';
import { auth0UserNameEnvVar, installAuth0Secret, installAuth0UISecret } from './auth0';
import type { Auth0Client } from './auth0types';
import { installParticipant } from './ledger';
import { exactNamespace, installCNHelmChart } from './utils';

export async function installValidator(
  auth0Client: Auth0Client,
  svc: k8s.helm.v3.Release,
  name: string
): Promise<k8s.helm.v3.Release> {
  const xns = exactNamespace(name);

  const postgresDb = postgres.installPostgres(xns, 'postgres');

  const participant = installParticipant(
    xns,
    'participant',
    postgresDb,
    [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
    [
      {
        actAs: [],
        admin: true,
        name: {
          env: 'CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME',
        },
        readAs: [],
      },
    ],
    [auth0UserNameEnvVar('validator')]
  );

  installCNHelmChart(xns, 'directory-web-ui', 'cn-directory-web-ui', {}, [
    await installAuth0UISecret(auth0Client, xns, 'directory', 'directory'),
  ]);

  installCNHelmChart(xns, 'splitwell-web-ui', 'cn-splitwell-web-ui', {}, [
    await installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell'),
  ]);

  const dependsOn = [
    svc,
    xns.ns,
    participant,
    await installAuth0Secret(auth0Client, xns, 'validator', 'validator'),
    await installAuth0Secret(auth0Client, xns, 'svc', 'svc'),
    await installAuth0UISecret(auth0Client, xns, 'wallet', 'wallet'),
  ];

  return installCNHelmChart(
    xns,
    'validator-' + xns.logicalName,
    'cn-validator',
    {
      participantAddress: 'participant',
      postgres: postgresDb,
      additionalUsers: [],
      validatorPartyHint: `${name}_validator_service_user`,
      appDars: [
        'cn-node-0.1.0-SNAPSHOT/dars/directory-service-0.1.0.dar',
        'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar',
      ],
    },
    dependsOn
  );
}
