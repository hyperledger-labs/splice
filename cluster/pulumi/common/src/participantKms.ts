import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
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

const createKmsServiceAccount = (xns: ExactNamespace, kmsConfig: KmsConfig) => {
  const condition = {
    title: `"${kmsConfig.keyRingId}" keyring`,
    description: '(managed by Pulumi)',
    expression: `resource.name.startsWith("projects/${kmsConfig.projectId}/locations/${kmsConfig.locationId}/keyRings/${kmsConfig.keyRingId}")`,
  };
  const kmsServiceAccount = new GcpServiceAccount(`${CLUSTER_BASENAME}-${xns.logicalName}-kms`, {
    accountId: `${CLUSTER_BASENAME}-${xns.logicalName}-kms`,
    displayName: `KMS Service Account (${CLUSTER_BASENAME} ${xns.logicalName})`,
    description: '(managed by Pulumi)',
    roles: [
      { id: 'roles/cloudkms.admin', condition },
      { id: 'roles/cloudkms.cryptoOperator', condition },
    ],
  });

  return new gcp.serviceaccount.Key('participantKmsServiceAccountKey', {
    serviceAccountId: kmsServiceAccount.name,
  });
};

export const getParticipantKmsHelmResources = (
  xns: ExactNamespace,
  kmsConfig: KmsConfig
): {
  kmsValues: ParticipantKmsHelmResources;
  kmsDependencies: pulumi.Resource[];
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
          input: createKmsServiceAccount(xns, kmsConfig).privateKey,
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

  // Note that by design, GCP keyrings cannot be deleted; a pulumi delete just deletes the resource.
  // So we might want a "get-or-create" pattern here. But that doesn't work: https://github.com/pulumi/pulumi/issues/3364
  // So please create the keyring yourself through the UI. Pick a single-region keyring that matches the region of your deployment.
  // The code below is just there to ensure that the keyring exists before deploying, which will make debugging easier.
  const keyRing = gcp.kms.KeyRing.get(
    `${kmsConfig.keyRingId}_keyring`,
    `projects/${kmsConfig.projectId}/locations/${kmsConfig.locationId}/keyRings/${kmsConfig.keyRingId}`,
    {
      name: kmsConfig.keyRingId,
      location: kmsConfig.locationId,
      project: kmsConfig.projectId,
    }
  );

  return {
    kmsValues,
    kmsDependencies: [gkeCredentialsSecret, keyRing],
  };
};
