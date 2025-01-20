import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';
import { Output } from '@pulumi/pulumi';

import { ArtifactoryCreds } from './artifactory';
import { installAuth0Secret, installAuth0UiSecretWithClientId } from './auth0';
import { Auth0Client } from './auth0types';
import { spliceConfig } from './config/config';
import { artifactories } from './config/consts';
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

export function imagePullSecretByNamespaceName(
  ns: string,
  retainOnDelete?: boolean,
  patchForce: 'true' | 'false' = 'true'
): pulumi.Resource[] {
  return imagePullSecretByNamespaceNameForServiceAccount(
    ns,
    'default',
    [],
    patchForce,
    retainOnDelete
  );
}

export function imagePullSecretByNamespaceNameForServiceAccount(
  ns: string,
  serviceAccountName: string,
  dependsOn: pulumi.Resource[] = [],
  patchForce: 'true' | 'false' = 'true',
  retainOnDelete?: boolean
): pulumi.Resource[] {
  const keys = ArtifactoryCreds.getCreds().creds;
  const k8sProvider = new k8s.Provider(
    'k8s-imgpull-' + ns,
    { enableServerSideApply: true },
    { retainOnDelete }
  );

  const allowedArtifactories = spliceConfig.pulumiProjectConfig.allowedArtifactories;

  type DockerConfig = { [key: string]: { auth: string; username: string; password: string } };

  const dockerConfigJson = pulumi.output(keys).apply(creds => {
    const auths: DockerConfig = {};

    allowedArtifactories.forEach(artName => {
      const art = artifactories[artName];
      auths[art] = {
        auth: btoa(creds.username + ':' + creds.password),
        username: creds.username,
        password: creds.password,
      };
    });
    return JSON.stringify({ auths });
  });

  const secret = new k8s.core.v1.Secret(
    ns + '-docker-reg-cred',
    {
      metadata: {
        name: 'docker-reg-cred',
        namespace: ns,
        // We may create this secret in multiple stacks; let's not fail on it already existing.
        annotations: {
          'pulumi.com/patchForce': patchForce,
        },
      },
      type: 'kubernetes.io/dockerconfigjson',
      stringData: {
        '.dockerconfigjson': dockerConfigJson,
      },
    },
    {
      dependsOn,
      retainOnDelete,
    }
  );
  return [
    secret,
    patchServiceAccountWithImagePullSecret(
      ns,
      serviceAccountName,
      secret.metadata.name,
      k8sProvider,
      retainOnDelete
    ),
  ];
}

function patchServiceAccountWithImagePullSecret(
  ns: string,
  serviceAccountName: string,
  secretName: Output<string>,
  k8sProvider: k8s.Provider,
  retainOnDelete?: boolean
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
      retainOnDelete,
    }
  );

  return patch;
}

export function imagePullSecret(
  ns: ExactNamespace,
  retainOnDelete: boolean = false,
  patchForce: 'true' | 'false' = 'true'
): CnInput<pulumi.Resource>[] {
  return imagePullSecretByNamespaceName(ns.logicalName, retainOnDelete, patchForce);
}

export function imagePullSecretForServiceAccount(
  ns: ExactNamespace,
  serviceAccountName: string,
  patchForce: 'true' | 'false' = 'true'
): CnInput<pulumi.Resource>[] {
  return imagePullSecretByNamespaceNameForServiceAccount(
    ns.logicalName,
    serviceAccountName,
    [],
    patchForce
  );
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
