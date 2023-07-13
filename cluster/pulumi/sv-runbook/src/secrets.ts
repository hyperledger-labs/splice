import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  ExactNamespace,
  installAuth0Secret,
  installAuth0UiSecretWithClientId,
  requireEnv,
} from 'cn-pulumi-common';

const walletUIClientId = 'l9MS11POtbvPaVvgzns3Tdj9IDnosLwl';
const svUIClientId = '8S8o4U6OYWWuw5vPCIpFQGzzWM2IpHkx';
const directoryClientId = 'iwZgud30aDMMUYpZc5caSnjNATWwITzp';

function uiSecret(
  auth0Client: Auth0Client,
  ns: ExactNamespace,
  appName: string,
  clientId: string
): k8s.core.v1.Secret {
  return installAuth0UiSecretWithClientId(auth0Client, ns, appName, appName, clientId);
}

const k8sProvider = new k8s.Provider('k8s', { enableServerSideApply: true });

export function imagePullSecretByNamespaceName(ns: string): pulumi.Resource[] {
  const artifactory = 'digitalasset-canton-network-docker.jfrog.io';
  const username = requireEnv('ARTIFACTORY_USER', 'Username for jfrog artifactory');
  const password = requireEnv('ARTIFACTORY_PASSWORD', 'Password for jfrog artifactory');
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

type AppAndUiSecrets = {
  appSecret: k8s.core.v1.Secret;
  uiSecret: k8s.core.v1.Secret;
};

export async function svValidatorSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client
): Promise<AppAndUiSecrets> {
  return {
    appSecret: await installAuth0Secret(auth0Client, ns, 'validator', 'validator'),
    uiSecret: uiSecret(auth0Client, ns, 'wallet', walletUIClientId),
  };
}

export function svDirectoryUiSecret(
  ns: ExactNamespace,
  auth0Client: Auth0Client
): k8s.core.v1.Secret {
  return uiSecret(auth0Client, ns, 'directory', directoryClientId);
}

export async function svAppSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client
): Promise<AppAndUiSecrets> {
  return {
    appSecret: await installAuth0Secret(auth0Client, ns, 'sv', 'sv'),
    uiSecret: uiSecret(auth0Client, ns, 'sv', svUIClientId),
  };
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
