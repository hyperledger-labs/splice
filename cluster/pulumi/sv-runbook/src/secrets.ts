import * as auth0 from '@pulumi/auth0';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { ExactNamespace, requiredEnv } from './utils';

// For now, uses canton-network-sv-test tenant, and assumes env variables AUTH0_DOMAIN, AUTH0_CLIENT_ID, AUTH0_CLIENT_SECRET
// With AUTH0_DOMAIN=canton-network-sv-test.us.auth0.com
// and ClientID and Secret copied from: https://manage.auth0.com/dashboard/us/canton-network-sv-test/apis/644fdcbfd1cecaff1c09e136/test

const sv1Auth0ClientId = 'bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn';
const sv1Auth0Secret = auth0.Client.get('sv1', sv1Auth0ClientId).clientSecret;
const validatorAuth0ClientId = 'uxeQGIBKueNDmugVs1RlMWEUZhZqyLyr';
const validatorAuth0Secret = auth0.Client.get('validator', validatorAuth0ClientId).clientSecret;
const walletUIClientId = 'l9MS11POtbvPaVvgzns3Tdj9IDnosLwl';
const svUIClientId = '8S8o4U6OYWWuw5vPCIpFQGzzWM2IpHkx';

function participantSecret(
  ns: ExactNamespace,
  appName: string,
  appAuth0ClientId: string
): k8s.core.v1.Secret {
  const secretName = 'cn-app-' + appName + '-ledger-api-auth';
  return new k8s.core.v1.Secret(
    secretName,
    {
      metadata: {
        name: secretName,
        namespace: ns.logicalName,
      },
      type: 'Opaque',
      data: {
        'ledger-api-user': btoa(appAuth0ClientId + '@clients'),
      },
    },
    {
      dependsOn: [ns.ns],
    }
  );
}

function appSecret(
  ns: ExactNamespace,
  appName: string,
  appAuth0ClientId: string,
  appAuth0Secret: pulumi.Output<string>
): k8s.core.v1.Secret {
  const secretName = 'cn-app-' + appName + '-ledger-api-auth';
  return new k8s.core.v1.Secret(
    secretName,
    {
      metadata: {
        name: secretName,
        namespace: ns.logicalName,
      },
      type: 'Opaque',
      data: {
        'ledger-api-user': btoa(appAuth0ClientId + '@clients'),
        url: btoa(
          'https://' +
            requiredEnv('AUTH0_DOMAIN', 'domain of your auth0 tenant') +
            '/.well-known/openid-configuration'
        ),
        'client-id': btoa(appAuth0ClientId),
        'client-secret': appAuth0Secret.apply(s => btoa(s)),
      },
    },
    {
      dependsOn: [ns.ns],
    }
  );
}

function uiSecret(ns: ExactNamespace, appName: string, clientId: string): k8s.core.v1.Secret {
  const secretName = 'cn-app-' + appName + '-auth';
  return new k8s.core.v1.Secret(
    secretName,
    {
      metadata: {
        name: secretName,
        namespace: ns.logicalName,
      },
      type: 'Opaque',
      data: {
        url: btoa('https://' + requiredEnv('AUTH0_DOMAIN', 'domain of your auth0 tenant')),
        'client-id': btoa(clientId),
      },
    },
    {
      dependsOn: [ns.ns],
    }
  );
}

const k8sProvider = new k8s.Provider('k8s', { enableServerSideApply: true });

export function imagePullSecretByNamespaceName(ns: string): pulumi.Resource[] {
  const artifactory = 'digitalasset-canton-network-docker.jfrog.io';
  const username = requiredEnv('ARTIFACTORY_USER', 'Username for jfrog artifactory');
  const password = requiredEnv('ARTIFACTORY_PASSWORD', 'Password for jfrog artifactory');
  const secret = new k8s.core.v1.Secret(ns + '-docker-reg-cred', {
    metadata: {
      name: 'docker-reg-cred',
      namespace: ns,
    },
    type: 'kubernetes.io/dockerconfigjson',
    stringData: {
      '.dockerconfigjson': JSON.stringify({
        auths: {
          [artifactory]: {
            auth: btoa(username + ':' + password),
            username: username,
            password: password,
          },
        },
      }),
    },
  });
  const patch = new k8s.core.v1.ServiceAccountPatch(
    ns + '-default',
    {
      imagePullSecrets: [
        {
          name: secret.metadata.name,
        },
      ],
      metadata: {
        name: 'default',
        namespace: ns,
      },
    },
    {
      provider: k8sProvider,
    }
  );

  return [secret, patch];
}

export function imagePullSecret(ns: ExactNamespace): pulumi.Resource[] {
  return imagePullSecretByNamespaceName(ns.logicalName);
}

export function sv1UserParticipantSecret(ns: ExactNamespace): k8s.core.v1.Secret {
  return participantSecret(ns, 'sv1', sv1Auth0ClientId);
}

export function sv1UserValidatorParticipantSecret(ns: ExactNamespace): k8s.core.v1.Secret {
  return participantSecret(ns, 'sv1-validator', validatorAuth0ClientId);
}

export function svValidatorSecrets(ns: ExactNamespace): k8s.core.v1.Secret[] {
  return [
    appSecret(ns, 'validator', validatorAuth0ClientId, validatorAuth0Secret),
    uiSecret(ns, 'wallet-ui', walletUIClientId),
  ];
}

export function svAppSecrets(ns: ExactNamespace): k8s.core.v1.Secret[] {
  return [
    appSecret(ns, 'sv', sv1Auth0ClientId, sv1Auth0Secret),
    uiSecret(ns, 'sv-ui', svUIClientId),
  ];
}

// TODO(#4374): get rid of the dummy secrets
export function scanUserParticipantSecret(ns: ExactNamespace): k8s.core.v1.Secret {
  return participantSecret(ns, 'scan', 'dummy');
}

export function directoryUserParticipantSecret(ns: ExactNamespace): k8s.core.v1.Secret {
  return participantSecret(ns, 'directory', 'dummy');
}

export function svcUserParticipantSecret(ns: ExactNamespace): k8s.core.v1.Secret {
  return participantSecret(ns, 'svc', 'dummy');
}

export function svKeySecret(
  ns: ExactNamespace,
  publicKey: string,
  privateKey: string
): k8s.core.v1.Secret {
  const secretName = 'cn-app-sv-key';
  return new k8s.core.v1.Secret(
    secretName,
    {
      metadata: {
        name: secretName,
        namespace: ns.logicalName,
      },
      type: 'Opaque',
      data: {
        public: btoa(publicKey),
        private: btoa(privateKey),
      },
    },
    {
      dependsOn: [ns.ns],
    }
  );
}
