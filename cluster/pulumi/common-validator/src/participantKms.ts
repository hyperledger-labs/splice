import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as std from '@pulumi/std';
import {
  CLUSTER_BASENAME,
  ExactNamespace,
  KmsConfig,
  loadYamlFromFile,
  REPO_ROOT,
  ServiceAccount,
} from 'splice-pulumi-common';

export type ParticipantKmsHelmResources = {
  kms: KmsConfig;
  additionalEnvVars: { name: string; value: string }[];
  extraVolumeMounts: { mountPath: string; name: string; subPath: string }[];
  extraVolumes: { name: string; secret: { secretName: string } }[];
};

const createKmsServiceAccount = (xns: ExactNamespace) => {
  const kmsServiceAccount = new ServiceAccount(`kms-${CLUSTER_BASENAME}-${xns.logicalName}`, {
    accountId: `kms-${CLUSTER_BASENAME}-${xns.logicalName}`,
    displayName: 'Participant KMS Service Account',
    description: 'Service account for KMS (managed by Pulumi)',
    roles: ['roles/cloudkms.admin', 'roles/cloudkms.cryptoOperator'],
  });

  return new gcp.serviceaccount.Key('kmsKey', {
    serviceAccountId: kmsServiceAccount.name,
  });
};

export const getParticipantKmsHelmResources = (
  xns: ExactNamespace,
  kmsConfig: KmsConfig
): {
  kmsValues: ParticipantKmsHelmResources;
  gkeCredentialsSecret: k8s.core.v1.Secret;
} => {
  const gkeCredentialsSecret = new k8s.core.v1.Secret('gke-credentials', {
    metadata: {
      name: 'gke-credentials',
      namespace: xns.logicalName,
    },
    type: 'Opaque',
    stringData: {
      googleCredentials: std
        .base64decodeOutput({
          input: createKmsServiceAccount(xns).privateKey,
        })
        .apply(invoke => invoke.result),
    },
  });

  // Note that our Pulumi code supports only GCP KMS for now
  const kmsValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/kms-participant-gcp-values.yaml`,
    {
      LOCATION_ID: kmsConfig.locationId,
      PROJECT_ID: kmsConfig.projectId,
      KEY_RING_ID: kmsConfig.keyRingId,
    }
  );

  return {
    kmsValues,
    gkeCredentialsSecret,
  };
};
