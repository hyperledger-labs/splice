import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as std from '@pulumi/std';
import {
  CLUSTER_BASENAME,
  ExactNamespace,
  KmsConfig,
  loadYamlFromFile,
  SPLICE_ROOT,
  GcpServiceAccount,
} from 'splice-pulumi-common';

export type ParticipantKmsHelmResources = {
  kms: KmsConfig;
  additionalEnvVars: { name: string; value: string }[];
  extraVolumeMounts: { mountPath: string; name: string; subPath: string }[];
  extraVolumes: { name: string; secret: { secretName: string } }[];
};

const createKmsServiceAccount = (xns: ExactNamespace) => {
  const kmsServiceAccount = new GcpServiceAccount(`${CLUSTER_BASENAME}-${xns.logicalName}-kms`, {
    accountId: `${CLUSTER_BASENAME}-${xns.logicalName}-kms`,
    displayName: `KMS Service Account (${CLUSTER_BASENAME} ${xns.logicalName})`,
    description: '(managed by Pulumi)',
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
    `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/kms-participant-gcp-values.yaml`,
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
