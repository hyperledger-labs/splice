// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import { addRoleToGcpServiceAccount } from '@lfdecentralizedtrust/splice-pulumi-common';

export type ServiceAccountAuthorizationConfig = {
  serviceAccountEmail: string;
  pulumiKeyringProjectId: string;
  pulumiKeyringRegion: string;
  dnsSaKeySecretName: string;
};

export function authorizeServiceAccount(
  projectId: string,
  config: ServiceAccountAuthorizationConfig
): gcp.projects.IAMMember[] {
  const { serviceAccountEmail, pulumiKeyringProjectId, pulumiKeyringRegion } = config;

  // TODO(canton-network-internal#753): review and consider moving to internal
  const roles = [
    'roles/cloudsql.admin',
    'roles/compute.viewer',
    'roles/container.serviceAgent',
    'roles/logging.privateLogViewer',
    'roles/storage.objectAdmin',
    'roles/viewer',
    {
      id: 'roles/secretmanager.secretAccessor',
      condition: {
        title: 'DNS SA key secret',
        description: '(managed by Pulumi)',
        expression: `
          resource.name.endsWith("${config.dnsSaKeySecretName}/versions/latest")
          `,
      },
    },
    {
      id: 'roles/secretmanager.secretAccessor',
      condition: {
        title: 'SV IDs',
        description: '(managed by Pulumi)',
        expression: `
          resource.name.endsWith("-id/versions/latest")
          `,
      },
    },
    {
      id: 'roles/secretmanager.secretAccessor',
      condition: {
        title: 'CometBft keys',
        description: '(managed by Pulumi)',
        expression: `
          resource.name.endsWith("-cometbft-keys/versions/latest")
          `,
      },
    },
    {
      id: 'roles/secretmanager.secretAccessor',
      condition: {
        title: 'Grafana keys',
        description: '(managed by Pulumi)',
        expression: `
          resource.name.endsWith("grafana-keys/versions/latest")
          `,
      },
    },
    {
      id: 'roles/secretmanager.secretAccessor',
      condition: {
        title: 'SA key secret',
        description: '(managed by Pulumi)',
        expression: `resource.name.endsWith("secrets/gcp-bucket-sa-key-secret/versions/latest")`,
      },
    },
    {
      id: 'roles/cloudkms.cryptoKeyEncrypterDecrypter',
      condition: {
        title: 'Pulumi KMS',
        description: '(managed by Pulumi)',
        expression: `resource.type == "cloudkms.googleapis.com/CryptoKey" &&
            resource.name.startsWith("projects/'${pulumiKeyringProjectId}'/locations/'${pulumiKeyringRegion}'/keyRings/pulumi")`,
      },
    },
  ];
  return roles.map(role =>
    addRoleToGcpServiceAccount('authorized-sa', projectId, serviceAccountEmail, role)
  );
}
