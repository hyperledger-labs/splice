import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';

import { ArtifactoryCreds } from './artifactory';
import { installAuth0Secret, installAuth0UiSecretWithClientId } from './auth0';
import { Auth0Client } from './auth0types';
import { config } from './config';
import { CnInput } from './helm';
import { btoa, ExactNamespace } from './utils';

export type SvIdKey = {
  publicKey: string;
  privateKey: string;
};

export type SvCometBftKeys = {
  nodePrivateKey: string;
  validatorPrivateKey: string;
  validatorPublicKey: string;
};

export type GrafanaKeys = {
  adminUser: string;
  adminPassword: string;
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

export function svCometBftKeysFromSecret(name: string): pulumi.Output<SvCometBftKeys> {
  const keyJson = getSecretVersionOutput({ secret: name });
  return keyJson.apply(k => {
    const secretData = k.secretData;
    const parsed = JSON.parse(secretData);
    return {
      nodePrivateKey: String(parsed.nodePrivateKey),
      validatorPrivateKey: String(parsed.validatorPrivateKey),
      validatorPublicKey: String(parsed.validatorPublicKey),
    };
  });
}

export function imagePullSecretByNamespaceName(ns: string): pulumi.Resource[] {
  return imagePullSecretByNamespaceNameForServiceAccount(ns, 'default');
}

export function imagePullSecretByNamespaceNameForServiceAccount(
  ns: string,
  serviceAccountName: string,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource[] {
  const publicArtifactory = 'digitalasset-canton-network-docker.jfrog.io';
  const privateArtifactory = 'digitalasset-canton-network-docker-dev.jfrog.io';
  const keys = ArtifactoryCreds.getCreds().creds;
  const clusterBaseName = config.requireEnv('GCP_CLUSTER_BASENAME');
  const k8sProvider = new k8s.Provider('k8s-imgpull-' + ns, { enableServerSideApply: true });
  const supportPrivateArtifactory =
    clusterBaseName.startsWith('scratch') || config.envFlag('SUPPORT_PRIVATE_ARTIFACTORY');

  const prodDockerConfig = keys.apply(creds =>
    JSON.stringify({
      auths: {
        [publicArtifactory]: {
          auth: btoa(creds.username + ':' + creds.password),
          username: creds.username,
          password: creds.password,
        },
      },
    })
  );

  const withPrivDockerConfig = keys.apply(creds =>
    JSON.stringify({
      auths: {
        [publicArtifactory]: {
          auth: btoa(creds.username + ':' + creds.password),
          username: creds.username,
          password: creds.password,
        },
        [privateArtifactory]: {
          auth: btoa(creds.username + ':' + creds.password),
          username: creds.username,
          password: creds.password,
        },
      },
    })
  );
  const dockerConfigJson = supportPrivateArtifactory ? withPrivDockerConfig : prodDockerConfig;
  const secret = new k8s.core.v1.Secret(
    ns + '-docker-reg-cred',
    {
      metadata: {
        name: 'docker-reg-cred',
        namespace: ns,
      },
      type: 'kubernetes.io/dockerconfigjson',
      stringData: {
        '.dockerconfigjson': dockerConfigJson,
      },
    },
    {
      dependsOn,
    }
  );
  return [
    secret,
    patchServiceAccountWithImagePullSecret(ns, serviceAccountName, 'docker-reg-cred', k8sProvider),
  ];
}

function patchServiceAccountWithImagePullSecret(
  ns: string,
  serviceAccountName: string,
  secretName: string,
  k8sProvider: k8s.Provider
): pulumi.Resource {
  const patch = new k8s.core.v1.ServiceAccountPatch(
    ns + '-' + serviceAccountName,
    {
      imagePullSecrets: [
        {
          name: secretName,
        },
      ],
      metadata: {
        name: serviceAccountName,
        namespace: ns,
      },
    },
    {
      provider: k8sProvider,
    }
  );

  return patch;
}

export function imagePullSecret(ns: ExactNamespace): CnInput<pulumi.Resource>[] {
  return imagePullSecretByNamespaceName(ns.logicalName);
}

export function imagePullSecretForServiceAccount(
  ns: ExactNamespace,
  serviceAccountName: string
): CnInput<pulumi.Resource>[] {
  return imagePullSecretByNamespaceNameForServiceAccount(ns.logicalName, serviceAccountName);
}

export function uiSecret(
  auth0Client: Auth0Client,
  ns: ExactNamespace,
  appName: string,
  clientId: string
): k8s.core.v1.Secret {
  installAuth0UiSecretWithClientId(auth0Client, ns, appName, appName, clientId, 'cn');
  return installAuth0UiSecretWithClientId(auth0Client, ns, appName, appName, clientId, 'splice');
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
  await installAuth0Secret(auth0Client, ns, 'validator', 'validator', 'cn');
  return {
    appSecret: await installAuth0Secret(auth0Client, ns, 'validator', 'validator', 'splice'),
    uiSecret: uiSecret(auth0Client, ns, 'wallet', clientId),
  };
}

export function cnsUiSecret(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
  clientId: string
): k8s.core.v1.Secret {
  return uiSecret(auth0Client, ns, 'cns', clientId);
}

export function svKeySecret(ns: ExactNamespace, keys: CnInput<SvIdKey>): k8s.core.v1.Secret {
  const logicalPulumiName = 'cn-app-sv-key';
  const secretName = 'splice-app-sv-key';
  const data = pulumi.output(keys).apply(ks => {
    return {
      public: btoa(ks.publicKey),
      private: btoa(ks.privateKey),
    };
  });
  return new k8s.core.v1.Secret(
    logicalPulumiName,
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

export function installPostgresPasswordSecret(
  ns: ExactNamespace,
  password: pulumi.Output<string>,
  secretName: string
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    `cn-app-${ns.logicalName}-${secretName}`,
    {
      metadata: {
        name: secretName,
        namespace: ns.logicalName,
      },
      type: 'Opaque',
      data: {
        postgresPassword: password.apply(p => btoa(p || '')), // password is undefined in dump-config
      },
    },
    {
      dependsOn: [ns.ns],
    }
  );
}
