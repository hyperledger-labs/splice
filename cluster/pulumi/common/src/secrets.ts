import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';

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
  const publicArtifactory = 'digitalasset-canton-network-docker.jfrog.io';
  const privateArtifactory = 'digitalasset-canton-network-docker-dev.jfrog.io';
  const username = config.requireEnv('ARTIFACTORY_USER', 'Username for jfrog artifactory');
  const password = config.requireEnv('ARTIFACTORY_PASSWORD', 'Password for jfrog artifactory');
  const clusterBaseName = config.requireEnv('GCP_CLUSTER_BASENAME');
  const k8sProvider = new k8s.Provider('k8s-imgpull-' + ns, { enableServerSideApply: true });

  const prodDockerConfig = JSON.stringify({
    auths: {
      [publicArtifactory]: {
        auth: btoa(username + ':' + password),
        username: username,
        password: password,
      },
    },
  });

  const scratchDockerConfig = JSON.stringify({
    auths: {
      [publicArtifactory]: {
        auth: btoa(username + ':' + password),
        username: username,
        password: password,
      },
      [privateArtifactory]: {
        auth: btoa(username + ':' + password),
        username: username,
        password: password,
      },
    },
  });
  const dockerConfigJson = clusterBaseName.startsWith('scratch')
    ? scratchDockerConfig
    : prodDockerConfig;
  const secret = new k8s.core.v1.Secret(ns + '-docker-reg-cred', {
    metadata: {
      name: 'docker-reg-cred',
      namespace: ns,
    },
    type: 'kubernetes.io/dockerconfigjson',
    stringData: {
      '.dockerconfigjson': dockerConfigJson,
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

export function imagePullSecret(ns: ExactNamespace): CnInput<pulumi.Resource>[] {
  return imagePullSecretByNamespaceName(ns.logicalName);
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
