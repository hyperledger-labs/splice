// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { DockerConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/dockerConfig';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';
import { Output } from '@pulumi/pulumi';

import { installAuth0Secret, installAuth0UiSecretWithClientId } from './auth0/auth0';
import { Auth0Client } from './auth0/auth0types';
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

export type SvCometBftGovernanceKey = {
  publicKey: string;
  privateKey: string;
};

export type GrafanaKeys = {
  adminUser: string;
  adminPassword: string;
};

export function svKeyFromSecret(secretName: string): pulumi.Output<SvIdKey> {
  const keyJson = getSecretVersionOutput({ secret: secretName });
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

export function svCometBftGovernanceKeyFromSecret(
  secretName: string
): pulumi.Output<SvCometBftGovernanceKey> {
  const keyJson = getSecretVersionOutput({ secret: secretName });
  return keyJson.apply(k => {
    const secretData = k.secretData;
    const parsed = JSON.parse(secretData);
    return {
      publicKey: String(parsed.public),
      privateKey: String(parsed.private),
    };
  });
}

export function imagePullSecretByNamespaceName(
  ns: string,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource[] {
  return imagePullSecretByNamespaceNameForServiceAccount(ns, 'default', dependsOn);
}

export function imagePullSecretByNamespaceNameForServiceAccount(
  ns: string,
  serviceAccountName: string,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource[] {
  // eslint-disable-next-line no-process-env
  const kubecfg = process.env['KUBECONFIG'];
  // k8sProvider saves the absolute path to kubeconfig if it's defined in KUBECONFIG env var, which makes
  // it not portable between machines, so we temporarily remove this env var to avoid that.
  // eslint-disable-next-line no-process-env
  kubecfg && delete process.env.KUBECONFIG;
  const k8sProvider = new k8s.Provider(`k8s-imgpull-${ns}-${serviceAccountName}`, {
    enableServerSideApply: true,
  });
  // eslint-disable-next-line no-process-env
  kubecfg && (process.env['KUBECONFIG'] = kubecfg);

  // We do this to avoid having to rename existing secrets
  const secretName =
    serviceAccountName === 'default' ? 'docker-reg-cred' : `${serviceAccountName}-docker-reg-cred`;
  const secret = DockerConfig.getConfig().createImagePullSecret(ns, secretName, dependsOn);
  return [
    secret,
    patchServiceAccountWithImagePullSecret(
      ns,
      serviceAccountName,
      secret.metadata.name,
      k8sProvider
    ),
  ];
}

function patchServiceAccountWithImagePullSecret(
  ns: string,
  serviceAccountName: string,
  secretName: Output<string>,
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
  return imagePullSecretByNamespaceName(ns.logicalName, [ns.ns]);
}

export function imagePullSecretWithNonDefaultServiceAccount(
  ns: ExactNamespace,
  serviceAccountName: string
): CnInput<pulumi.Resource>[] {
  const serviceAccount = new k8s.core.v1.ServiceAccount(
    serviceAccountName,
    {
      metadata: {
        name: serviceAccountName,
        namespace: ns.logicalName,
      },
    },
    {
      dependsOn: ns.ns,
    }
  );
  return imagePullSecretByNamespaceNameForServiceAccount(ns.logicalName, serviceAccountName, [
    serviceAccount,
  ]);
}

export function uiSecret(
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

export function svCometBftGovernanceKeySecret(
  xns: ExactNamespace,
  keys: CnInput<SvCometBftGovernanceKey>
): k8s.core.v1.Secret {
  const secretName = 'splice-app-sv-cometbft-governance-key';
  const data = pulumi.output(keys).apply(ks => {
    return {
      public: btoa(ks.publicKey),
      private: btoa(ks.privateKey),
    };
  });
  return new k8s.core.v1.Secret(
    `splice-app-${xns.logicalName}-cometbft-governance-key`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: data,
    },
    {
      dependsOn: [xns.ns],
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
