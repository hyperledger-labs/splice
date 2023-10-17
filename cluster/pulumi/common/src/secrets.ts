import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';

import { installAuth0Secret, installAuth0UiSecretWithClientId } from './auth0';
import { Auth0Client } from './auth0types';
import { btoa, ExactNamespace, requireEnv } from './utils';

export type SvIdKey = {
  publicKey: string;
  privateKey: string;
};

export function svKeyFromSecret(sv: string): pulumi.Output<SvIdKey> {
  const keyJson = getSecretVersionOutput({ secret: `${sv}-id` });
  return keyJson.apply(k => {
    const secretData = k.secretData;
    const parsed = JSON.parse(secretData);
    return {
      publicKey: String(parsed.publicKey),
      privateKey: String(parsed.privateKey),
    };
  });
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

export function imagePullSecret(ns: ExactNamespace): pulumi.Input<pulumi.Resource>[] {
  return imagePullSecretByNamespaceName(ns.logicalName);
}

function uiSecret(
  auth0Client: Auth0Client,
  ns: ExactNamespace,
  appName: string,
  clientId: string
): k8s.core.v1.Secret {
  return installAuth0UiSecretWithClientId(auth0Client, ns, appName, appName, clientId);
}

export type AppAndUiSecrets = {
  appSecret: k8s.core.v1.Secret;
  uiSecret: k8s.core.v1.Secret;
};

export async function validatorSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
  clientId: string
): Promise<AppAndUiSecrets> {
  return {
    appSecret: await installAuth0Secret(auth0Client, ns, 'validator', 'validator'),
    uiSecret: uiSecret(auth0Client, ns, 'wallet', clientId),
  };
}

export function directoryUiSecret(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
  clientId: string
): k8s.core.v1.Secret {
  return uiSecret(auth0Client, ns, 'directory', clientId);
}

export async function svAppSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
  clientId: string
): Promise<AppAndUiSecrets> {
  return {
    appSecret: await installAuth0Secret(auth0Client, ns, 'sv', 'sv'),
    uiSecret: uiSecret(auth0Client, ns, 'sv', clientId),
  };
}

export function svKeySecret(ns: ExactNamespace, keys: pulumi.Input<SvIdKey>): k8s.core.v1.Secret {
  const secretName = 'cn-app-sv-key';
  const data = pulumi.output(keys).apply(ks => {
    return {
      public: btoa(ks.publicKey),
      private: btoa(ks.privateKey),
    };
  });
  return new k8s.core.v1.Secret(
    secretName,
    {
      metadata: {
        name: secretName,
        namespace: ns.logicalName,
      },
      type: 'Opaque',
      data: data,
    },
    {
      dependsOn: [ns.ns],
    }
  );
}
