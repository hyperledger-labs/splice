import * as k8s from '@pulumi/kubernetes';
import {
  auth0UserNameEnvVar,
  installAuth0Secret,
  installAuth0UISecret,
  exactNamespace,
  installCNHelmChart,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installParticipant } from './ledger';

export async function installValidator(
  auth0Client: Auth0Client,
  svc: k8s.helm.v3.Release,
  name: string,
  validatorWalletUser?: string
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

  installCNHelmChart(xns, 'splitwell-web-ui', 'cn-splitwell-web-ui', {}, [
    await installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell'),
  ]);

  const dependsOn = [
    svc,
    xns.ns,
    participant,
    await installAuth0Secret(auth0Client, xns, 'validator', 'validator'),
    await installAuth0UISecret(auth0Client, xns, 'wallet', 'wallet'),
    await installAuth0UISecret(auth0Client, xns, 'directory', 'directory'),
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
      globalDomainUrl: 'http://global-domain.sv-1:5008',
      validatorWalletUser,
    },
    dependsOn
  );
}
